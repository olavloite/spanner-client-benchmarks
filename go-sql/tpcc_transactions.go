package main

import (
	"context"
	"database/sql"
	"fmt"
	"math/rand"
	"strings"
	"sync"
	"time"

	"cloud.google.com/go/spanner"
	"github.com/google/uuid"
	spannerdriver "github.com/googleapis/go-sql-spanner"
	"google.golang.org/api/iterator"
)

func setTransactionTag(connection *sql.Conn, tag string) error {
	return connection.Raw(func(driverConnection any) error {
		spannerConnection, ok := driverConnection.(spannerdriver.SpannerConn)
		if !ok {
			return fmt.Errorf("unexpected driver connection %T, expected SpannerConn", driverConnection)
		}
		return spannerConnection.SetTransactionTag(tag)
	})
}

func setReadOnlyStaleness(connection *sql.Conn, staleness spanner.TimestampBound) error {
	return connection.Raw(func(driverConnection any) error {
		spannerConnection, ok := driverConnection.(spannerdriver.SpannerConn)
		if !ok {
			return fmt.Errorf("unexpected driver connection %T, expected SpannerConn", driverConnection)
		}
		return spannerConnection.SetReadOnlyStaleness(staleness)
	})
}

func executeNewOrder(ctx context.Context, connection *sql.Conn, scaleFactor int, totalItems int, extended bool) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	customerID := int64(rand.Intn(3000) + 1)
	numItems := rand.Intn(11) + 5 // 5 to 15 items

	itemIDs := make([]int64, numItems)
	quantities := make([]int64, numItems)
	for i := 0; i < numItems; i++ {
		itemIDs[i] = int64(rand.Intn(totalItems) + 1)
		quantities[i] = int64(rand.Intn(10) + 1)
	}

	if err := setTransactionTag(connection, "new_order"); err != nil {
		return err
	}
	transaction, err := connection.BeginTx(ctx, &sql.TxOptions{})
	if err != nil {
		return err
	}
	defer func() {
		if transaction != nil {
			_, _ = transaction.ExecContext(ctx, "ABORT BATCH")
			_ = transaction.Rollback()
		}
	}()

	// Read District Next Order ID
	var nextOrderID int64 = 1000
	rowsDistrict, err := transaction.QueryContext(ctx, "SELECT next_order_id FROM district WHERE warehouse_id = ? AND district_id = ? FOR UPDATE", warehouseID, districtID)
	if err != nil {
		return err
	}
	if rowsDistrict.Next() {
		if err := rowsDistrict.Scan(&nextOrderID); err != nil {
			_ = rowsDistrict.Close()
			return err
		}
	}
	_ = rowsDistrict.Close()
	if err := rowsDistrict.Err(); err != nil {
		return err
	}

	// Read Customer
	var discount float64
	var lastName string
	rowsCustomer, err := transaction.QueryContext(ctx, "SELECT discount, last_name FROM customer WHERE warehouse_id = ? AND district_id = ? AND customer_id = ?", warehouseID, districtID, customerID)
	if err != nil {
		return err
	}
	if rowsCustomer.Next() {
		if err := rowsCustomer.Scan(&discount, &lastName); err != nil {
			_ = rowsCustomer.Close()
			return err
		}
	}
	_ = rowsCustomer.Close()
	if err := rowsCustomer.Err(); err != nil {
		return err
	}

	now := time.Now()

	if _, err := transaction.ExecContext(ctx, "START BATCH DML"); err != nil {
		return err
	}

	if _, err := transaction.ExecContext(ctx, "UPDATE district SET next_order_id = ? WHERE warehouse_id = ? AND district_id = ?", nextOrderID+1, warehouseID, districtID); err != nil {
		return err
	}

	if _, err := transaction.ExecContext(ctx, "INSERT INTO orders (warehouse_id, district_id, order_id, customer_id, entry_date, item_count, all_local) VALUES (?, ?, ?, ?, ?, ?, 1)", warehouseID, districtID, nextOrderID, customerID, now, int64(numItems)); err != nil {
		return err
	}

	if _, err := transaction.ExecContext(ctx, "INSERT INTO new_orders (warehouse_id, district_id, order_id, created_timestamp) VALUES (?, ?, ?, ?)", warehouseID, districtID, nextOrderID, now); err != nil {
		return err
	}

	for i := 0; i < numItems; i++ {
		if _, err := transaction.ExecContext(ctx, "INSERT INTO order_line (warehouse_id, district_id, order_id, order_line_id, item_id, quantity, amount, dist_info) VALUES (?, ?, ?, ?, ?, ?, 25.0, 'distinfo')", warehouseID, districtID, nextOrderID, int64(i+1), itemIDs[i], quantities[i]); err != nil {
			return err
		}
		if _, err := transaction.ExecContext(ctx, "UPDATE stock SET quantity = quantity - ?, order_count = order_count + 1 WHERE warehouse_id = ? AND item_id = ?", quantities[i], warehouseID, itemIDs[i]); err != nil {
			return err
		}
	}

	if _, err := transaction.ExecContext(ctx, "RUN BATCH"); err != nil {
		return err
	}

	if err := transaction.Commit(); err != nil {
		return err
	}
	transaction = nil
	return nil
}

func executePayment(ctx context.Context, connection *sql.Conn, scaleFactor int, extended bool) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	customerID := int64(rand.Intn(3000) + 1)
	amount := rand.Float64()*4999.0 + 1.0

	if err := setTransactionTag(connection, "payment"); err != nil {
		return err
	}
	transaction, err := connection.BeginTx(ctx, &sql.TxOptions{})
	if err != nil {
		return err
	}
	defer func() {
		if transaction != nil {
			_, _ = transaction.ExecContext(ctx, "ABORT BATCH")
			_ = transaction.Rollback()
		}
	}()

	if _, err := transaction.ExecContext(ctx, "START BATCH DML"); err != nil {
		return err
	}

	if _, err := transaction.ExecContext(ctx, "UPDATE warehouse SET ytd = ytd + ? WHERE warehouse_id = ?", amount, warehouseID); err != nil {
		return err
	}
	if _, err := transaction.ExecContext(ctx, "UPDATE district SET ytd = ytd + ? WHERE warehouse_id = ? AND district_id = ?", amount, warehouseID, districtID); err != nil {
		return err
	}
	if _, err := transaction.ExecContext(ctx, "UPDATE customer SET balance = balance - ?, ytd_payment = ytd_payment + ?, payment_count = payment_count + 1 WHERE warehouse_id = ? AND district_id = ? AND customer_id = ?", amount, amount, warehouseID, districtID, customerID); err != nil {
		return err
	}
	if _, err := transaction.ExecContext(ctx, "INSERT INTO history (warehouse_id, district_id, history_id, customer_id, date, amount, data) VALUES (?, ?, GENERATE_UUID(), ?, ?, ?, 'history')", warehouseID, districtID, customerID, time.Now(), amount); err != nil {
		return err
	}

	if _, err := transaction.ExecContext(ctx, "RUN BATCH"); err != nil {
		return err
	}

	if err := transaction.Commit(); err != nil {
		return err
	}
	transaction = nil
	return nil
}

func executeOrderStatus(ctx context.Context, connection *sql.Conn, scaleFactor int, extended bool) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	customerID := int64(rand.Intn(3000) + 1)

	if extended {
		if err := setReadOnlyStaleness(connection, spanner.ExactStaleness(15*time.Second)); err != nil {
			return err
		}
		defer func() {
			_ = setReadOnlyStaleness(connection, spanner.StrongRead())
		}()
	}

	transaction, err := connection.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return err
	}
	defer func() {
		if transaction != nil {
			_ = transaction.Rollback()
		}
	}()

	var balance float64
	var firstName, lastName string
	rowsCustomer, err := transaction.QueryContext(ctx, "SELECT balance, first_name, last_name FROM customer WHERE warehouse_id = ? AND district_id = ? AND customer_id = ?", warehouseID, districtID, customerID)
	if err != nil {
		return err
	}
	for rowsCustomer.Next() {
		if err := rowsCustomer.Scan(&balance, &firstName, &lastName); err != nil {
			_ = rowsCustomer.Close()
			return err
		}
	}
	_ = rowsCustomer.Close()
	if err := rowsCustomer.Err(); err != nil {
		return err
	}

	var orderID int64 = -1
	rowsOrder, err := transaction.QueryContext(ctx, "SELECT order_id FROM orders WHERE warehouse_id = ? AND district_id = ? AND customer_id = ? ORDER BY order_id DESC LIMIT 1", warehouseID, districtID, customerID)
	if err != nil {
		return err
	}
	if rowsOrder.Next() {
		if err := rowsOrder.Scan(&orderID); err != nil {
			_ = rowsOrder.Close()
			return err
		}
	}
	_ = rowsOrder.Close()
	if err := rowsOrder.Err(); err != nil {
		return err
	}

	if orderID != -1 {
		rowsLine, err := transaction.QueryContext(ctx, "SELECT order_line_id, item_id, quantity, amount FROM order_line WHERE warehouse_id = ? AND district_id = ? AND order_id = ?", warehouseID, districtID, orderID)
		if err != nil {
			return err
		}
		var orderLineID, itemID, quantity int64
		var amount float64
		for rowsLine.Next() {
			if err := rowsLine.Scan(&orderLineID, &itemID, &quantity, &amount); err != nil {
				_ = rowsLine.Close()
				return err
			}
		}
		_ = rowsLine.Close()
		if err := rowsLine.Err(); err != nil {
			return err
		}
	}

	if err := transaction.Commit(); err != nil {
		return err
	}
	transaction = nil
	return nil
}

func executeDelivery(ctx context.Context, connection *sql.Conn, scaleFactor int, extended bool) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	carrierID := int64(rand.Intn(10) + 1)

	if err := setTransactionTag(connection, "delivery"); err != nil {
		return err
	}
	transaction, err := connection.BeginTx(ctx, &sql.TxOptions{})
	if err != nil {
		return err
	}
	defer func() {
		if transaction != nil {
			_, _ = transaction.ExecContext(ctx, "ABORT BATCH")
			_ = transaction.Rollback()
		}
	}()

	type orderMatch struct {
		districtID int64
		orderID    int64
	}
	var matchedOrders []orderMatch

	for districtID := int64(1); districtID <= 10; districtID++ {
		rows, err := transaction.QueryContext(ctx, "SELECT order_id FROM new_orders WHERE warehouse_id = ? AND district_id = ? ORDER BY created_timestamp ASC LIMIT 1 FOR UPDATE", warehouseID, districtID)
		if err != nil {
			return err
		}
		if rows.Next() {
			var orderID int64
			if err := rows.Scan(&orderID); err != nil {
				_ = rows.Close()
				return err
			}
			matchedOrders = append(matchedOrders, orderMatch{districtID: districtID, orderID: orderID})
		}
		_ = rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
	}

	if len(matchedOrders) > 0 {
		if _, err := transaction.ExecContext(ctx, "START BATCH DML"); err != nil {
			return err
		}
		now := time.Now()
		for _, match := range matchedOrders {
			if _, err := transaction.ExecContext(ctx, "DELETE FROM new_orders WHERE warehouse_id = ? AND district_id = ? AND order_id = ?", warehouseID, match.districtID, match.orderID); err != nil {
				return err
			}
			if _, err := transaction.ExecContext(ctx, "UPDATE orders SET carrier_id = ? WHERE warehouse_id = ? AND district_id = ? AND order_id = ?", carrierID, warehouseID, match.districtID, match.orderID); err != nil {
				return err
			}
			if _, err := transaction.ExecContext(ctx, "UPDATE order_line SET delivery_date = ? WHERE warehouse_id = ? AND district_id = ? AND order_id = ?", now, warehouseID, match.districtID, match.orderID); err != nil {
				return err
			}
		}
		if _, err := transaction.ExecContext(ctx, "RUN BATCH"); err != nil {
			return err
		}
	}

	if err := transaction.Commit(); err != nil {
		return err
	}
	transaction = nil
	return nil
}

func executeStockLevel(ctx context.Context, connection *sql.Conn, scaleFactor int, extended bool) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	threshold := int64(rand.Intn(6) + 15)

	transaction, err := connection.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return err
	}
	defer func() {
		if transaction != nil {
			_ = transaction.Rollback()
		}
	}()

	var nextOrderID int64
	rowsDistrict, err := transaction.QueryContext(ctx, "SELECT next_order_id FROM district WHERE warehouse_id = ? AND district_id = ?", warehouseID, districtID)
	if err != nil {
		return err
	}
	if rowsDistrict.Next() {
		if err := rowsDistrict.Scan(&nextOrderID); err != nil {
			_ = rowsDistrict.Close()
			return err
		}
	}
	_ = rowsDistrict.Close()
	if err := rowsDistrict.Err(); err != nil {
		return err
	}

	if nextOrderID > 0 {
		minOrderID := nextOrderID - 20
		if minOrderID < 1 {
			minOrderID = 1
		}

		rowsStock, err := transaction.QueryContext(ctx, "SELECT COUNT(DISTINCT s.item_id) FROM order_line ol JOIN stock s ON s.warehouse_id = ol.warehouse_id AND s.item_id = ol.item_id WHERE ol.warehouse_id = ? AND ol.district_id = ? AND ol.order_id >= ? AND ol.order_id < ? AND s.quantity < ?", warehouseID, districtID, minOrderID, nextOrderID, threshold)
		if err != nil {
			return err
		}
		if rowsStock.Next() {
			var count int64
			if err := rowsStock.Scan(&count); err != nil {
				_ = rowsStock.Close()
				return err
			}
		}
		_ = rowsStock.Close()
		if err := rowsStock.Err(); err != nil {
			return err
		}
	}

	if err := transaction.Commit(); err != nil {
		return err
	}
	transaction = nil
	return nil
}

func executeNewOrderMutations(ctx context.Context, connection *sql.Conn, scaleFactor, totalItems int) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	customerID := int64(rand.Intn(3000) + 1)
	numItems := rand.Intn(11) + 5 // 5 to 15 items

	itemIDs := make([]int64, numItems)
	quantities := make([]int64, numItems)
	for i := 0; i < numItems; i++ {
		itemIDs[i] = int64(rand.Intn(totalItems) + 1)
		quantities[i] = int64(rand.Intn(10) + 1)
	}

	if err := setTransactionTag(connection, "new_order_mutations"); err != nil {
		return err
	}
	transaction, err := connection.BeginTx(ctx, &sql.TxOptions{})
	if err != nil {
		return err
	}
	defer func() {
		if transaction != nil {
			_ = transaction.Rollback()
		}
	}()

	var nextOrderID int64 = 1000
	rowsDistrict, err := transaction.QueryContext(ctx, "SELECT next_order_id FROM district WHERE warehouse_id = ? AND district_id = ?", warehouseID, districtID)
	if err != nil {
		return err
	}
	if rowsDistrict.Next() {
		if err := rowsDistrict.Scan(&nextOrderID); err != nil {
			_ = rowsDistrict.Close()
			return err
		}
	}
	_ = rowsDistrict.Close()
	if err := rowsDistrict.Err(); err != nil {
		return err
	}

	var discount float64
	var lastName string
	rowsCustomer, err := transaction.QueryContext(ctx, "SELECT discount, last_name FROM customer WHERE warehouse_id = ? AND district_id = ? AND customer_id = ?", warehouseID, districtID, customerID)
	if err != nil {
		return err
	}
	if rowsCustomer.Next() {
		if err := rowsCustomer.Scan(&discount, &lastName); err != nil {
			_ = rowsCustomer.Close()
			return err
		}
	}
	_ = rowsCustomer.Close()
	if err := rowsCustomer.Err(); err != nil {
		return err
	}

	stockQuantities := make(map[int64]int64)
	placeholders := make([]string, numItems)
	arguments := make([]any, numItems+1)
	arguments[0] = warehouseID
	for i := range itemIDs {
		placeholders[i] = "?"
		arguments[i+1] = itemIDs[i]
	}
	stockSql := fmt.Sprintf("SELECT item_id, quantity FROM stock WHERE warehouse_id = ? AND item_id IN (%s)", strings.Join(placeholders, ","))
	rowsStock, err := transaction.QueryContext(ctx, stockSql, arguments...)
	if err != nil {
		return err
	}
	for rowsStock.Next() {
		var itemID, quantity int64
		if err := rowsStock.Scan(&itemID, &quantity); err != nil {
			_ = rowsStock.Close()
			return err
		}
		stockQuantities[itemID] = quantity
	}
	_ = rowsStock.Close()
	if err := rowsStock.Err(); err != nil {
		return err
	}

	now := time.Now()
	mutations := []*spanner.Mutation{
		spanner.Update("district", []string{"warehouse_id", "district_id", "next_order_id"}, []interface{}{warehouseID, districtID, nextOrderID + 1}),
		spanner.Insert("orders", []string{"warehouse_id", "district_id", "order_id", "customer_id", "entry_date", "item_count", "all_local"}, []interface{}{warehouseID, districtID, nextOrderID, customerID, now, int64(numItems), int64(1)}),
		spanner.Insert("new_orders", []string{"warehouse_id", "district_id", "order_id", "created_timestamp"}, []interface{}{warehouseID, districtID, nextOrderID, now}),
	}
	for i := 0; i < numItems; i++ {
		itemID := itemIDs[i]
		quantity := quantities[i]
		stockQuantity, ok := stockQuantities[itemID]
		if !ok {
			stockQuantity = 10
		}
		newQuantity := stockQuantity - quantity
		mutations = append(mutations, spanner.Insert("order_line", []string{"warehouse_id", "district_id", "order_id", "order_line_id", "item_id", "quantity", "amount", "dist_info"}, []interface{}{warehouseID, districtID, nextOrderID, int64(i + 1), itemID, quantity, 25.0, "distinfo"}))
		mutations = append(mutations, spanner.Update("stock", []string{"warehouse_id", "item_id", "quantity"}, []interface{}{warehouseID, itemID, newQuantity}))
	}

	if err := connection.Raw(func(driverConnection interface{}) error {
		spannerConnection, ok := driverConnection.(spannerdriver.SpannerConn)
		if !ok {
			return fmt.Errorf("unexpected driver connection %T, expected SpannerConn", driverConnection)
		}
		return spannerConnection.BufferWrite(mutations)
	}); err != nil {
		return err
	}

	if err := transaction.Commit(); err != nil {
		return err
	}
	transaction = nil
	return nil
}

func executePaymentMutationsDirect(ctx context.Context, connection *sql.Conn, scaleFactor int) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	customerID := int64(rand.Intn(3000) + 1)
	amount := rand.Float64()*4999.0 + 1.0

	if err := setTransactionTag(connection, "payment_mutations_direct"); err != nil {
		return err
	}
	transaction, err := connection.BeginTx(ctx, &sql.TxOptions{})
	if err != nil {
		return err
	}
	defer func() {
		if transaction != nil {
			_, _ = transaction.ExecContext(ctx, "ABORT BATCH")
			_ = transaction.Rollback()
		}
	}()

	if _, err := transaction.ExecContext(ctx, "START BATCH DML"); err != nil {
		return err
	}
	if _, err := transaction.ExecContext(ctx, "UPDATE warehouse SET ytd = ytd + ? WHERE warehouse_id = ?", amount, warehouseID); err != nil {
		return err
	}
	if _, err := transaction.ExecContext(ctx, "UPDATE district SET ytd = ytd + ? WHERE warehouse_id = ? AND district_id = ?", amount, warehouseID, districtID); err != nil {
		return err
	}
	if _, err := transaction.ExecContext(ctx, "UPDATE customer SET balance = balance - ?, ytd_payment = ytd_payment + ?, payment_count = payment_count + 1 WHERE warehouse_id = ? AND district_id = ? AND customer_id = ?", amount, amount, warehouseID, districtID, customerID); err != nil {
		return err
	}
	if _, err := transaction.ExecContext(ctx, "RUN BATCH"); err != nil {
		return err
	}

	if err := transaction.Commit(); err != nil {
		return err
	}
	transaction = nil

	historyMutation := spanner.Insert("history", []string{"warehouse_id", "district_id", "history_id", "customer_id", "date", "amount", "data"}, []interface{}{warehouseID, districtID, uuid.NewString(), customerID, time.Now(), amount, "history"})
	return connection.Raw(func(driverConnection interface{}) error {
		spannerConnection, ok := driverConnection.(spannerdriver.SpannerConn)
		if !ok {
			return fmt.Errorf("unexpected driver connection %T, expected SpannerConn", driverConnection)
		}
		_, err := spannerConnection.Apply(ctx, []*spanner.Mutation{historyMutation})
		return err
	})
}

func executeOrderStatusReads(ctx context.Context, connection *sql.Conn, scaleFactor int) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	customerID := int64(rand.Intn(3000) + 1)

	var client *spanner.Client
	if err := connection.Raw(func(driverConnection interface{}) error {
		spannerConnection, ok := driverConnection.(spannerdriver.SpannerConn)
		if !ok {
			return fmt.Errorf("unexpected driver connection %T, expected SpannerConn", driverConnection)
		}
		var err error
		client, err = spannerConnection.UnderlyingClient()
		return err
	}); err != nil {
		return err
	}

	readOnlyTransaction := client.ReadOnlyTransaction().WithTimestampBound(spanner.ExactStaleness(15 * time.Second))
	defer readOnlyTransaction.Close()

	readOptions := spanner.ReadOptions{RequestTag: "order_status_reads"}
	row, err := readOnlyTransaction.ReadRowWithOptions(ctx, "customer", spanner.Key{warehouseID, districtID, customerID}, []string{"balance", "first_name", "last_name"}, &readOptions)
	if err != nil {
		return err
	}
	var balance float64
	var firstName, lastName string
	if err := row.Column(0, &balance); err != nil {
		return err
	}
	if err := row.Column(1, &firstName); err != nil {
		return err
	}
	if err := row.Column(2, &lastName); err != nil {
		return err
	}

	queryOptions := spanner.QueryOptions{RequestTag: "order_status_reads"}
	orderIterator := readOnlyTransaction.QueryWithOptions(ctx, spanner.Statement{
		SQL:    "SELECT order_id FROM orders WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c ORDER BY order_id DESC LIMIT 1",
		Params: map[string]interface{}{"w": warehouseID, "d": districtID, "c": customerID},
	}, queryOptions)
	orderRow, err := orderIterator.Next()
	if err != nil && err != iterator.Done {
		orderIterator.Stop()
		return err
	}
	orderIterator.Stop()

	if orderRow != nil {
		var orderID int64
		if err := orderRow.ColumnByName("order_id", &orderID); err != nil {
			return err
		}

		keyRange := spanner.KeyRange{
			Start: spanner.Key{warehouseID, districtID, orderID},
			End:   spanner.Key{warehouseID, districtID, orderID + 1},
			Kind:  spanner.ClosedOpen,
		}
		lineIterator := readOnlyTransaction.ReadWithOptions(ctx, "order_line", keyRange, []string{"order_line_id", "item_id", "quantity", "amount"}, &readOptions)
		defer lineIterator.Stop()

		var orderLineID, itemID, quantity int64
		var amount float64
		for {
			lineRow, err := lineIterator.Next()
			if err == iterator.Done {
				break
			}
			if err != nil {
				return err
			}
			if err := lineRow.Column(0, &orderLineID); err != nil {
				return err
			}
			if err := lineRow.Column(1, &itemID); err != nil {
				return err
			}
			if err := lineRow.Column(2, &quantity); err != nil {
				return err
			}
			if err := lineRow.Column(3, &amount); err != nil {
				return err
			}
		}
	}

	return nil
}

func executeStockLevelPartitioned(ctx context.Context, connection *sql.Conn, scaleFactor int) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	threshold := int64(rand.Intn(6) + 15)

	var client *spanner.Client
	if err := connection.Raw(func(driverConnection interface{}) error {
		spannerConnection, ok := driverConnection.(spannerdriver.SpannerConn)
		if !ok {
			return fmt.Errorf("unexpected driver connection %T, expected SpannerConn", driverConnection)
		}
		var err error
		client, err = spannerConnection.UnderlyingClient()
		return err
	}); err != nil {
		return err
	}

	batchTransaction, err := client.BatchReadOnlyTransaction(ctx, spanner.ExactStaleness(15*time.Second))
	if err != nil {
		return err
	}
	defer batchTransaction.Close()

	queryOptions := spanner.QueryOptions{RequestTag: "stock_level_partitioned"}
	districtIterator := batchTransaction.QueryWithOptions(ctx, spanner.Statement{
		SQL:    "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d",
		Params: map[string]interface{}{"w": warehouseID, "d": districtID},
	}, queryOptions)
	row, err := districtIterator.Next()
	if err != nil && err != iterator.Done {
		districtIterator.Stop()
		return err
	}
	districtIterator.Stop()

	if row != nil {
		var nextOrderID int64
		if err := row.ColumnByName("next_order_id", &nextOrderID); err != nil {
			return err
		}

		minOrderID := nextOrderID - 20
		if minOrderID < 1 {
			minOrderID = 1
		}

		partitionStatement := spanner.Statement{
			SQL: "SELECT DISTINCT s.item_id FROM order_line ol JOIN stock s ON s.warehouse_id = ol.warehouse_id AND s.item_id = ol.item_id WHERE ol.warehouse_id = @w AND ol.district_id = @d AND ol.order_id >= @minOrderID AND ol.order_id < @nextOrderID AND s.quantity < @threshold",
			Params: map[string]interface{}{
				"w":           warehouseID,
				"d":           districtID,
				"minOrderID":  minOrderID,
				"nextOrderID": nextOrderID,
				"threshold":   threshold,
			},
		}

		partitions, err := batchTransaction.PartitionQueryWithOptions(ctx, partitionStatement, spanner.PartitionOptions{}, queryOptions)
		if err != nil {
			return err
		}

		var mutex sync.Mutex
		uniqueItemIDs := make(map[int64]bool)
		errorChannel := make(chan error, len(partitions))

		var waitGroup sync.WaitGroup
		for _, partition := range partitions {
			waitGroup.Add(1)
			go func(part *spanner.Partition) {
				defer waitGroup.Done()
				rowIterator := batchTransaction.Execute(ctx, part)
				defer rowIterator.Stop()
				for {
					stockRow, err := rowIterator.Next()
					if err == iterator.Done {
						break
					}
					if err != nil {
						errorChannel <- err
						return
					}
					var itemID int64
					if err := stockRow.Column(0, &itemID); err != nil {
						errorChannel <- err
						return
					}
					mutex.Lock()
					uniqueItemIDs[itemID] = true
					mutex.Unlock()
				}
			}(partition)
		}
		waitGroup.Wait()

		select {
		case err := <-errorChannel:
			return err
		default:
		}
		_ = len(uniqueItemIDs)
	}

	return nil
}
