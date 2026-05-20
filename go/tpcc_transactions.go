package main

import (
	"context"
	"math/rand"
	"time"

	"cloud.google.com/go/spanner"
	"google.golang.org/api/iterator"
)

func executeNewOrder(ctx context.Context, client *spanner.Client, scaleFactor int, totalItems int) error {
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

	_, err := client.ReadWriteTransaction(ctx, func(ctx context.Context, tx *spanner.ReadWriteTransaction) error {
		// Read District Next Order ID
		stmt := spanner.Statement{
			SQL:    "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d",
			Params: map[string]interface{}{"w": warehouseID, "d": districtID},
		}
		iter := tx.Query(ctx, stmt)
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
		iterCust := tx.Query(ctx, custStmt)
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
			if _, err := tx.BatchUpdate(ctx, stmts); err != nil {
				return err
			}
		}

		return nil
	})
	return err
}

func executePayment(ctx context.Context, client *spanner.Client, scaleFactor int) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	customerID := int64(rand.Intn(3000) + 1)
	amount := rand.Float64()*4999.0 + 1.0

	_, err := client.ReadWriteTransaction(ctx, func(ctx context.Context, tx *spanner.ReadWriteTransaction) error {
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

		_, err := tx.BatchUpdate(ctx, stmts)
		return err
	})
	return err
}

func executeOrderStatus(ctx context.Context, client *spanner.Client, scaleFactor int) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	customerID := int64(rand.Intn(3000) + 1)

	// Explicit Multi-Use ReadOnlyTransaction to guarantee snapshot consistency across queries
	tx := client.ReadOnlyTransaction()
	defer tx.Close()

	iterCust := tx.Query(ctx, spanner.Statement{
		SQL:    "SELECT balance, first_name, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c",
		Params: map[string]interface{}{"w": warehouseID, "d": districtID, "c": customerID},
	})
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

	iterOrder := tx.Query(ctx, spanner.Statement{
		SQL:    "SELECT order_id FROM orders WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c ORDER BY order_id DESC LIMIT 1",
		Params: map[string]interface{}{"w": warehouseID, "d": districtID, "c": customerID},
	})
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

		iterLine := tx.Query(ctx, spanner.Statement{
			SQL:    "SELECT order_line_id, item_id, quantity, amount FROM order_line WHERE warehouse_id = @w AND district_id = @d AND order_id = @o",
			Params: map[string]interface{}{"w": warehouseID, "d": districtID, "o": orderID},
		})
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
	}

	return nil
}

func executeDelivery(ctx context.Context, client *spanner.Client, scaleFactor int) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	carrierID := int64(rand.Intn(10) + 1)

	_, err := client.ReadWriteTransaction(ctx, func(ctx context.Context, tx *spanner.ReadWriteTransaction) error {
		var batchStmts []spanner.Statement
		for districtID := int64(1); districtID <= 10; districtID++ {
			iter := tx.Query(ctx, spanner.Statement{
				SQL:    "SELECT order_id FROM new_orders WHERE warehouse_id = @w AND district_id = @d ORDER BY created_timestamp ASC LIMIT 1",
				Params: map[string]interface{}{"w": warehouseID, "d": districtID},
			})
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
			if _, err := tx.BatchUpdate(ctx, batchStmts); err != nil {
				return err
			}
		}
		return nil
	})
	return err
}

func executeStockLevel(ctx context.Context, client *spanner.Client, scaleFactor int) error {
	warehouseID := int64(rand.Intn(scaleFactor) + 1)
	districtID := int64(rand.Intn(10) + 1)
	threshold := int64(rand.Intn(6) + 15)

	tx := client.ReadOnlyTransaction()
	defer tx.Close()

	iterDist := tx.Query(ctx, spanner.Statement{
		SQL:    "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d",
		Params: map[string]interface{}{"w": warehouseID, "d": districtID},
	})
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

		iterStock := tx.Query(ctx, spanner.Statement{
			SQL: "SELECT COUNT(DISTINCT s.item_id) FROM order_line ol JOIN stock s ON s.warehouse_id = ol.warehouse_id AND s.item_id = ol.item_id WHERE ol.warehouse_id = @w AND ol.district_id = @d AND ol.order_id >= @minOrderID AND ol.order_id < @nextOrderID AND s.quantity < @threshold",
			Params: map[string]interface{}{
				"w":           warehouseID,
				"d":           districtID,
				"minOrderID":  minOrderID,
				"nextOrderID": nextOrderID,
				"threshold":   threshold,
			},
		})
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
