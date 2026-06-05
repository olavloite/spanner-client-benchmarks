package main

import (
	"context"
	"math/rand"
	"sync"
	"time"

	"cloud.google.com/go/spanner"
	"github.com/google/uuid"
	"google.golang.org/api/iterator"
)

func executeNewOrder(ctx context.Context, client *spanner.Client, scaleFactor int, totalItems int, extended bool) error {
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

	txOpts := spanner.TransactionOptions{TransactionTag: "new_order"}

	_, err := client.ReadWriteTransactionWithOptions(ctx, func(ctx context.Context, tx *spanner.ReadWriteTransaction) error {
		qopts := spanner.QueryOptions{RequestTag: "new_order"}

		// Read District Next Order ID
		stmt := spanner.Statement{
			SQL:    "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d FOR UPDATE",
			Params: map[string]interface{}{"w": warehouseID, "d": districtID},
		}
		iter := tx.QueryWithOptions(ctx, stmt, qopts)
		row, err := iter.Next()
		if err != nil && err != iterator.Done {
			iter.Stop()
			return err
		}
		iter.Stop()

		var nextOrderID int64 = 1000
		if row != nil {
			if err := row.ColumnByName("next_order_id", &nextOrderID); err != nil {
				return err
			}
		}

		// Read Customer
		custStmt := spanner.Statement{
			SQL:    "SELECT discount, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c",
			Params: map[string]interface{}{"w": warehouseID, "d": districtID, "c": customerID},
		}
		iterCust := tx.QueryWithOptions(ctx, custStmt, qopts)
		var discount float64
		var lastName string
		for {
			custRow, err := iterCust.Next()
			if err == iterator.Done {
				break
			}
			if err != nil {
				iterCust.Stop()
				return err
			}
			_ = custRow.Column(0, &discount)
			_ = custRow.Column(1, &lastName)
		}
		iterCust.Stop()

		now := time.Now()
		var stmts []spanner.Statement

		stmts = append(stmts, spanner.Statement{
			SQL:    "UPDATE district SET next_order_id = @next WHERE warehouse_id = @w AND district_id = @d",
			Params: map[string]interface{}{"next": nextOrderID + 1, "w": warehouseID, "d": districtID},
		})

		stmts = append(stmts, spanner.Statement{
			SQL: "INSERT INTO orders (warehouse_id, district_id, order_id, customer_id, entry_date, item_count, all_local) VALUES (@w, @d, @o, @c, @dt, @cnt, 1)",
			Params: map[string]interface{}{
				"w":   warehouseID,
				"d":   districtID,
				"o":   nextOrderID,
				"c":   customerID,
				"dt":  now,
				"cnt": int64(numItems),
			},
		})

		stmts = append(stmts, spanner.Statement{
			SQL: "INSERT INTO new_orders (warehouse_id, district_id, order_id, created_timestamp) VALUES (@w, @d, @o, @dt)",
			Params: map[string]interface{}{
				"w":  warehouseID,
				"d":  districtID,
				"o":  nextOrderID,
				"dt": now,
			},
		})

		// Batch insert order lines and update stock
		for i := 0; i < numItems; i++ {
			stmts = append(stmts, spanner.Statement{
				SQL: "INSERT INTO order_line (warehouse_id, district_id, order_id, order_line_id, item_id, quantity, amount, dist_info) VALUES (@w, @d, @o, @ol, @i, @qty, @amt, 'distinfo')",
				Params: map[string]interface{}{
					"w":   warehouseID,
					"d":   districtID,
					"o":   nextOrderID,
					"ol":  int64(i + 1),
					"i":   itemIDs[i],
					"qty": quantities[i],
					"amt": 25.0,
				},
			})
			stmts = append(stmts, spanner.Statement{
				SQL: "UPDATE stock SET quantity = quantity - @qty, order_count = order_count + 1 WHERE warehouse_id = @w AND item_id = @i",
				Params: map[string]interface{}{
					"qty": quantities[i],
					"w":   warehouseID,
					"i":   itemIDs[i],
				},
			})
		}

		if len(stmts) > 0 {
			if _, err := tx.BatchUpdateWithOptions(ctx, stmts, qopts); err != nil {
				return err
			}
		}

		return nil
	}, txOpts)
	return err
}

func executePayment(ctx context.Context, client *spanner.Client, scaleFactor int, extended bool) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	customerID := int64(rand.Intn(3000) + 1)
	amount := rand.Float64()*4999.0 + 1.0

	txOpts := spanner.TransactionOptions{TransactionTag: "payment"}

	_, err := client.ReadWriteTransactionWithOptions(ctx, func(ctx context.Context, tx *spanner.ReadWriteTransaction) error {
		stmts := []spanner.Statement{
			{
				SQL:    "UPDATE warehouse SET ytd = ytd + @amt WHERE warehouse_id = @w",
				Params: map[string]interface{}{"amt": amount, "w": warehouseID},
			},
			{
				SQL:    "UPDATE district SET ytd = ytd + @amt WHERE warehouse_id = @w AND district_id = @d",
				Params: map[string]interface{}{"amt": amount, "w": warehouseID, "d": districtID},
			},
			{
				SQL:    "UPDATE customer SET balance = balance - @amt, ytd_payment = ytd_payment + @amt, payment_count = payment_count + 1 WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c",
				Params: map[string]interface{}{"amt": amount, "w": warehouseID, "d": districtID, "c": customerID},
			},
			{
				SQL: "INSERT INTO history (warehouse_id, district_id, history_id, customer_id, date, amount, data) VALUES (@w, @d, GENERATE_UUID(), @c, @dt, @amt, 'history')",
				Params: map[string]interface{}{
					"w":   warehouseID,
					"d":   districtID,
					"c":   customerID,
					"dt":  time.Now(),
					"amt": amount,
				},
			},
		}

		qopts := spanner.QueryOptions{RequestTag: "payment"}
		_, err := tx.BatchUpdateWithOptions(ctx, stmts, qopts)
		return err
	}, txOpts)
	return err
}

func executeOrderStatus(ctx context.Context, client *spanner.Client, scaleFactor int, extended bool) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	customerID := int64(rand.Intn(3000) + 1)

	var tx *spanner.ReadOnlyTransaction
	if extended {
		tx = client.ReadOnlyTransaction().WithTimestampBound(spanner.ExactStaleness(15 * time.Second))
	} else {
		tx = client.ReadOnlyTransaction()
	}
	defer tx.Close()

	qopts := spanner.QueryOptions{RequestTag: "order_status"}

	iterCust := tx.QueryWithOptions(ctx, spanner.Statement{
		SQL:    "SELECT balance, first_name, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c",
		Params: map[string]interface{}{"w": warehouseID, "d": districtID, "c": customerID},
	}, qopts)
	var balance float64
	var firstName, lastName string
	for {
		custRow, err := iterCust.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			iterCust.Stop()
			return err
		}
		_ = custRow.Column(0, &balance)
		_ = custRow.Column(1, &firstName)
		_ = custRow.Column(2, &lastName)
	}
	iterCust.Stop()

	iterOrder := tx.QueryWithOptions(ctx, spanner.Statement{
		SQL:    "SELECT order_id FROM orders WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c ORDER BY order_id DESC LIMIT 1",
		Params: map[string]interface{}{"w": warehouseID, "d": districtID, "c": customerID},
	}, qopts)
	row, err := iterOrder.Next()
	if err != nil && err != iterator.Done {
		iterOrder.Stop()
		return err
	}
	iterOrder.Stop()

	if row != nil {
		var orderID int64
		if err := row.ColumnByName("order_id", &orderID); err != nil {
			return err
		}

		iterLine := tx.QueryWithOptions(ctx, spanner.Statement{
			SQL:    "SELECT order_line_id, item_id, quantity, amount FROM order_line WHERE warehouse_id = @w AND district_id = @d AND order_id = @o",
			Params: map[string]interface{}{"w": warehouseID, "d": districtID, "o": orderID},
		}, qopts)
		var orderLineID, itemID, quantity int64
		var amount float64
		for {
			lineRow, err := iterLine.Next()
			if err == iterator.Done {
				break
			}
			if err != nil {
				iterLine.Stop()
				return err
			}
			_ = lineRow.Column(0, &orderLineID)
			_ = lineRow.Column(1, &itemID)
			_ = lineRow.Column(2, &quantity)
			_ = lineRow.Column(3, &amount)
		}
		iterLine.Stop()
	}

	return nil
}

func executeDelivery(ctx context.Context, client *spanner.Client, scaleFactor int, extended bool) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	carrierID := int64(rand.Intn(10) + 1)

	txOpts := spanner.TransactionOptions{TransactionTag: "delivery"}

	_, err := client.ReadWriteTransactionWithOptions(ctx, func(ctx context.Context, tx *spanner.ReadWriteTransaction) error {
		qopts := spanner.QueryOptions{RequestTag: "delivery"}

		var batchStmts []spanner.Statement
		for districtID := int64(1); districtID <= 10; districtID++ {
			iter := tx.QueryWithOptions(ctx, spanner.Statement{
				SQL:    "SELECT order_id FROM new_orders WHERE warehouse_id = @w AND district_id = @d ORDER BY created_timestamp ASC LIMIT 1 FOR UPDATE",
				Params: map[string]interface{}{"w": warehouseID, "d": districtID},
			}, qopts)
			row, err := iter.Next()
			if err != nil && err != iterator.Done {
				iter.Stop()
				return err
			}
			iter.Stop()

			if row != nil {
				var orderID int64
				if err := row.ColumnByName("order_id", &orderID); err != nil {
					return err
				}

				batchStmts = append(batchStmts, spanner.Statement{
					SQL:    "DELETE FROM new_orders WHERE warehouse_id = @w AND district_id = @d AND order_id = @o",
					Params: map[string]interface{}{"w": warehouseID, "d": districtID, "o": orderID},
				})
				batchStmts = append(batchStmts, spanner.Statement{
					SQL:    "UPDATE orders SET carrier_id = @c WHERE warehouse_id = @w AND district_id = @d AND order_id = @o",
					Params: map[string]interface{}{"c": carrierID, "w": warehouseID, "d": districtID, "o": orderID},
				})
				batchStmts = append(batchStmts, spanner.Statement{
					SQL:    "UPDATE order_line SET delivery_date = @dt WHERE warehouse_id = @w AND district_id = @d AND order_id = @o",
					Params: map[string]interface{}{"dt": time.Now(), "w": warehouseID, "d": districtID, "o": orderID},
				})
			}
		}

		if len(batchStmts) > 0 {
			if _, err := tx.BatchUpdateWithOptions(ctx, batchStmts, qopts); err != nil {
				return err
			}
		}
		return nil
	}, txOpts)
	return err
}

func executeStockLevel(ctx context.Context, client *spanner.Client, scaleFactor int, extended bool) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	threshold := int64(rand.Intn(6) + 15)

	tx := client.ReadOnlyTransaction()
	defer tx.Close()

	qopts := spanner.QueryOptions{RequestTag: "stock_level"}

	iterDist := tx.QueryWithOptions(ctx, spanner.Statement{
		SQL:    "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d",
		Params: map[string]interface{}{"w": warehouseID, "d": districtID},
	}, qopts)
	row, err := iterDist.Next()
	if err != nil && err != iterator.Done {
		iterDist.Stop()
		return err
	}
	iterDist.Stop()

	if row != nil {
		var nextOrderID int64
		if err := row.ColumnByName("next_order_id", &nextOrderID); err != nil {
			return err
		}

		minOrderID := nextOrderID - 20
		if minOrderID < 1 {
			minOrderID = 1
		}

		iterStock := tx.QueryWithOptions(ctx, spanner.Statement{
			SQL: "SELECT COUNT(DISTINCT s.item_id) FROM order_line ol JOIN stock s ON s.warehouse_id = ol.warehouse_id AND s.item_id = ol.item_id WHERE ol.warehouse_id = @w AND ol.district_id = @d AND ol.order_id >= @minOrderID AND ol.order_id < @nextOrderID AND s.quantity < @threshold",
			Params: map[string]interface{}{
				"w":           warehouseID,
				"d":           districtID,
				"minOrderID":  minOrderID,
				"nextOrderID": nextOrderID,
				"threshold":   threshold,
			},
		}, qopts)
		rowStock, err := iterStock.Next()
		if err != nil && err != iterator.Done {
			iterStock.Stop()
			return err
		}
		iterStock.Stop()
		if rowStock != nil {
			var count int64
			_ = rowStock.Column(0, &count)
		}
	}
	return nil
}

func executeNewOrderMutations(ctx context.Context, client *spanner.Client, scaleFactor, totalItems int) error {
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

	_, err := client.ReadWriteTransactionWithOptions(ctx, func(ctx context.Context, tx *spanner.ReadWriteTransaction) error {
		ropts := spanner.ReadOptions{RequestTag: "new_order_mutations"}

		// Read District Next Order ID via ReadRow API
		row, err := tx.ReadRowWithOptions(ctx, "district", spanner.Key{warehouseID, districtID}, []string{"next_order_id"}, &ropts)
		if err != nil {
			return err
		}
		var nextOrderID int64
		if err := row.Column(0, &nextOrderID); err != nil {
			return err
		}

		// Read Customer discount and last name via ReadRow API
		rowCust, err := tx.ReadRowWithOptions(ctx, "customer", spanner.Key{warehouseID, districtID, customerID}, []string{"discount", "last_name"}, &ropts)
		if err != nil {
			return err
		}
		var discount float64
		var lastName string
		if err := rowCust.Column(0, &discount); err != nil {
			return err
		}
		if err := rowCust.Column(1, &lastName); err != nil {
			return err
		}

		// Read Stock quantities for all items in a single Read
		var keys []spanner.Key
		for _, itemID := range itemIDs {
			keys = append(keys, spanner.Key{warehouseID, itemID})
		}
		iterStock := tx.ReadWithOptions(ctx, "stock", spanner.KeySetFromKeys(keys...), []string{"item_id", "quantity"}, &ropts)
		defer iterStock.Stop()

		stockQuantities := make(map[int64]int64)
		for {
			rowStock, err := iterStock.Next()
			if err == iterator.Done {
				break
			}
			if err != nil {
				return err
			}
			var itemID, quantity int64
			if err := rowStock.Column(0, &itemID); err != nil {
				return err
			}
			if err := rowStock.Column(1, &quantity); err != nil {
				return err
			}
			stockQuantities[itemID] = quantity
		}

		now := time.Now()
		mutations := []*spanner.Mutation{
			spanner.Update("district", []string{"warehouse_id", "district_id", "next_order_id"}, []interface{}{warehouseID, districtID, nextOrderID + 1}),
			spanner.Insert("orders", []string{"warehouse_id", "district_id", "order_id", "customer_id", "entry_date", "item_count", "all_local"}, []interface{}{warehouseID, districtID, nextOrderID, customerID, now, int64(numItems), int64(1)}),
			spanner.Insert("new_orders", []string{"warehouse_id", "district_id", "order_id", "created_timestamp"}, []interface{}{warehouseID, districtID, nextOrderID, now}),
		}

		for i := 0; i < numItems; i++ {
			itemID := itemIDs[i]
			qty := quantities[i]
			stockQty := stockQuantities[itemID]
			newQty := stockQty - qty

			mutations = append(mutations, spanner.Insert("order_line", []string{"warehouse_id", "district_id", "order_id", "order_line_id", "item_id", "quantity", "amount", "dist_info"}, []interface{}{warehouseID, districtID, nextOrderID, int64(i + 1), itemID, qty, 25.0, "distinfo"}))
			mutations = append(mutations, spanner.Update("stock", []string{"warehouse_id", "item_id", "quantity"}, []interface{}{warehouseID, itemID, newQty}))
		}

		return tx.BufferWrite(mutations)
	}, spanner.TransactionOptions{TransactionTag: "new_order_mutations"})
	return err
}

func executePaymentMutationsDirect(ctx context.Context, client *spanner.Client, scaleFactor int) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	customerID := int64(rand.Intn(3000) + 1)
	amount := rand.Float64()*4999.0 + 1.0

	_, err := client.ReadWriteTransactionWithOptions(ctx, func(ctx context.Context, tx *spanner.ReadWriteTransaction) error {
		stmts := []spanner.Statement{
			{
				SQL:    "UPDATE warehouse SET ytd = ytd + @amt WHERE warehouse_id = @w",
				Params: map[string]interface{}{"amt": amount, "w": warehouseID},
			},
			{
				SQL:    "UPDATE district SET ytd = ytd + @amt WHERE warehouse_id = @w AND district_id = @d",
				Params: map[string]interface{}{"amt": amount, "w": warehouseID, "d": districtID},
			},
			{
				SQL:    "UPDATE customer SET balance = balance - @amt, ytd_payment = ytd_payment + @amt, payment_count = payment_count + 1 WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c",
				Params: map[string]interface{}{"amt": amount, "w": warehouseID, "d": districtID, "c": customerID},
			},
		}

		_, err := tx.BatchUpdate(ctx, stmts)
		return err
	}, spanner.TransactionOptions{TransactionTag: "payment_mutations_direct"})
	if err != nil {
		return err
	}

	m := spanner.Insert("history", []string{"warehouse_id", "district_id", "history_id", "customer_id", "date", "amount", "data"}, []interface{}{warehouseID, districtID, uuid.NewString(), customerID, time.Now(), amount, "history"})
	_, err = client.Apply(ctx, []*spanner.Mutation{m})
	return err
}

func executeOrderStatusReads(ctx context.Context, client *spanner.Client, scaleFactor int) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	customerID := int64(rand.Intn(3000) + 1)

	tx := client.ReadOnlyTransaction().WithTimestampBound(spanner.ExactStaleness(15 * time.Second))
	defer tx.Close()

	ropts := spanner.ReadOptions{RequestTag: "order_status_reads"}

	// 1. Look up the customer's balance, first_name, and last_name using ReadRow API
	row, err := tx.ReadRowWithOptions(ctx, "customer", spanner.Key{warehouseID, districtID, customerID}, []string{"balance", "first_name", "last_name"}, &ropts)
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

	// 2. Query the latest order ID using query
	qopts := spanner.QueryOptions{RequestTag: "order_status_reads"}
	iterOrder := tx.QueryWithOptions(ctx, spanner.Statement{
		SQL:    "SELECT order_id FROM orders WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c ORDER BY order_id DESC LIMIT 1",
		Params: map[string]interface{}{"w": warehouseID, "d": districtID, "c": customerID},
	}, qopts)
	rowOrder, err := iterOrder.Next()
	if err != nil && err != iterator.Done {
		iterOrder.Stop()
		return err
	}
	iterOrder.Stop()

	if rowOrder != nil {
		var orderID int64
		if err := rowOrder.ColumnByName("order_id", &orderID); err != nil {
			return err
		}

		// 3. Look up all matching order_line records using Read key range prefix
		// closedOpen key range from {warehouseID, districtID, orderID} to {warehouseID, districtID, orderID + 1}
		keySet := spanner.KeyRange{
			Start: spanner.Key{warehouseID, districtID, orderID},
			End:   spanner.Key{warehouseID, districtID, orderID + 1},
			Kind:  spanner.ClosedOpen,
		}
		iterLine := tx.ReadWithOptions(ctx, "order_line", keySet, []string{"order_line_id", "item_id", "quantity", "amount"}, &ropts)
		defer iterLine.Stop()

		var orderLineID, itemID, quantity int64
		var amount float64
		for {
			lineRow, err := iterLine.Next()
			if err == iterator.Done {
				break
			}
			if err != nil {
				return err
			}
			_ = lineRow.Column(0, &orderLineID)
			_ = lineRow.Column(1, &itemID)
			_ = lineRow.Column(2, &quantity)
			_ = lineRow.Column(3, &amount)
		}
	}

	return nil
}

func executeStockLevelPartitioned(ctx context.Context, client *spanner.Client, scaleFactor int) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	threshold := int64(rand.Intn(6) + 15)

	txn, err := client.BatchReadOnlyTransaction(ctx, spanner.ExactStaleness(15*time.Second))
	if err != nil {
		return err
	}
	defer txn.Close()

	// First read district next_order_id using query inside BatchReadOnlyTransaction
	qopts := spanner.QueryOptions{RequestTag: "stock_level_partitioned"}
	iterDist := txn.QueryWithOptions(ctx, spanner.Statement{
		SQL:    "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d",
		Params: map[string]interface{}{"w": warehouseID, "d": districtID},
	}, qopts)
	row, err := iterDist.Next()
	if err != nil && err != iterator.Done {
		iterDist.Stop()
		return err
	}
	iterDist.Stop()

	if row != nil {
		var nextOrderID int64
		if err := row.ColumnByName("next_order_id", &nextOrderID); err != nil {
			return err
		}

		minOrderID := nextOrderID - 20
		if minOrderID < 1 {
			minOrderID = 1
		}

		partitionStmt := spanner.Statement{
			SQL: "SELECT DISTINCT s.item_id FROM order_line ol JOIN stock s ON s.warehouse_id = ol.warehouse_id AND s.item_id = ol.item_id WHERE ol.warehouse_id = @w AND ol.district_id = @d AND ol.order_id >= @minOrderID AND ol.order_id < @nextOrderID AND s.quantity < @threshold",
			Params: map[string]interface{}{
				"w":           warehouseID,
				"d":           districtID,
				"minOrderID":  minOrderID,
				"nextOrderID": nextOrderID,
				"threshold":   threshold,
			},
		}

		// Partition query
		partitions, err := txn.PartitionQueryWithOptions(ctx, partitionStmt, spanner.PartitionOptions{}, qopts)
		if err != nil {
			return err
		}

		var mu sync.Mutex
		uniqueItemIDs := make(map[int64]bool)
		errs := make(chan error, len(partitions))

		var wg sync.WaitGroup
		for _, p := range partitions {
			wg.Add(1)
			go func(part *spanner.Partition) {
				defer wg.Done()
				iter := txn.Execute(ctx, part)
				defer iter.Stop()
				for {
					rowStock, err := iter.Next()
					if err == iterator.Done {
						break
					}
					if err != nil {
						errs <- err
						return
					}
					var itemID int64
					if err := rowStock.Column(0, &itemID); err != nil {
						errs <- err
						return
					}
					mu.Lock()
					uniqueItemIDs[itemID] = true
					mu.Unlock()
				}
			}(p)
		}
		wg.Wait()

		select {
		case err := <-errs:
			return err
		default:
		}
		_ = len(uniqueItemIDs)
	}

	return nil
}
