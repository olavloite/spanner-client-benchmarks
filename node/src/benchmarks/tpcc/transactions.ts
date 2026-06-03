import {Database, Spanner} from '@google-cloud/spanner';
const {v4: uuidv4} = require('uuid');

export async function executeNewOrder(
  database: Database,
  scaleFactor: number,
  totalItems: number
): Promise<void> {
  const warehouseId = Math.floor(Math.random() * scaleFactor) + 1;
  const districtId = Math.floor(Math.random() * 10) + 1;
  const customerId = Math.floor(Math.random() * 3000) + 1;
  const numItems = Math.floor(Math.random() * 11) + 5;

  const itemIds: number[] = [];
  const quantities: number[] = [];
  for (let i = 0; i < numItems; i++) {
    itemIds.push(Math.floor(Math.random() * totalItems) + 1);
    quantities.push(Math.floor(Math.random() * 10) + 1);
  }

  await database.runTransactionAsync(async transaction => {
    const getDistrict = {
      sql: 'SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d',
      params: {w: warehouseId, d: districtId},
      types: {w: 'int64', d: 'int64'},
    };
    const [distRows] = await transaction.run(getDistrict);
    let nextOrderId = 1000;
    if (distRows.length > 0) {
      const dRow = distRows[0].toJSON();
      nextOrderId = Number(dRow.next_order_id);
    }

    const getCust = {
      sql: 'SELECT discount, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c',
      params: {w: warehouseId, d: districtId, c: customerId},
      types: {w: 'int64', d: 'int64', c: 'int64'},
    };
    const [custRows] = await transaction.run(getCust);
    let discount = 0.0;
    let lastName = '';
    if (custRows.length > 0) {
      const cRow = custRows[0].toJSON();
      discount = Number(cRow.discount);
      lastName = String(cRow.last_name);
    }

    const now = new Date();
    const batchStatements: any[] = [
      {
        sql: 'UPDATE district SET next_order_id = @next WHERE warehouse_id = @w AND district_id = @d',
        params: {next: nextOrderId + 1, w: warehouseId, d: districtId},
        types: {next: 'int64', w: 'int64', d: 'int64'},
      },
      {
        sql: 'INSERT INTO orders (warehouse_id, district_id, order_id, customer_id, entry_date, item_count, all_local) VALUES (@w, @d, @o, @c, @dt, @cnt, 1)',
        params: {
          w: warehouseId,
          d: districtId,
          o: nextOrderId,
          c: customerId,
          dt: now,
          cnt: numItems,
        },
        types: {
          w: 'int64',
          d: 'int64',
          o: 'int64',
          c: 'int64',
          dt: 'timestamp',
          cnt: 'int64',
        },
      },
      {
        sql: 'INSERT INTO new_orders (warehouse_id, district_id, order_id, created_timestamp) VALUES (@w, @d, @o, @dt)',
        params: {w: warehouseId, d: districtId, o: nextOrderId, dt: now},
        types: {w: 'int64', d: 'int64', o: 'int64', dt: 'timestamp'},
      },
    ];

    for (let i = 0; i < numItems; i++) {
      batchStatements.push({
        sql: "INSERT INTO order_line (warehouse_id, district_id, order_id, order_line_id, item_id, quantity, amount, dist_info) VALUES (@w, @d, @o, @ol, @i, @qty, @amt, 'distinfo')",
        params: {
          w: warehouseId,
          d: districtId,
          o: nextOrderId,
          ol: i + 1,
          i: itemIds[i],
          qty: quantities[i],
          amt: Spanner.float(25.0),
        },
        types: {
          w: 'int64',
          d: 'int64',
          o: 'int64',
          ol: 'int64',
          i: 'int64',
          qty: 'int64',
          amt: 'float64',
        },
      });
      batchStatements.push({
        sql: 'UPDATE stock SET quantity = quantity - @qty, order_count = order_count + 1 WHERE warehouse_id = @w AND item_id = @i',
        params: {qty: quantities[i], w: warehouseId, i: itemIds[i]},
        types: {qty: 'int64', w: 'int64', i: 'int64'},
      });
    }

    if (batchStatements.length > 0) {
      await transaction.batchUpdate(batchStatements);
    }

    await transaction.commit();
  });
}

export async function executePayment(
  database: Database,
  scaleFactor: number
): Promise<void> {
  const warehouseId = Math.floor(Math.random() * scaleFactor) + 1;
  const districtId = Math.floor(Math.random() * 10) + 1;
  const customerId = Math.floor(Math.random() * 3000) + 1;
  const amount = Math.random() * 4999.0 + 1.0;

  await database.runTransactionAsync(async transaction => {
    const stmts = [
      {
        sql: 'UPDATE warehouse SET ytd = ytd + @amt WHERE warehouse_id = @w',
        params: {amt: Spanner.float(amount), w: warehouseId},
        types: {amt: 'float64', w: 'int64'},
      },
      {
        sql: 'UPDATE district SET ytd = ytd + @amt WHERE warehouse_id = @w AND district_id = @d',
        params: {amt: Spanner.float(amount), w: warehouseId, d: districtId},
        types: {amt: 'float64', w: 'int64', d: 'int64'},
      },
      {
        sql: 'UPDATE customer SET balance = balance - @amt, ytd_payment = ytd_payment + @amt, payment_count = payment_count + 1 WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c',
        params: {
          amt: Spanner.float(amount),
          w: warehouseId,
          d: districtId,
          c: customerId,
        },
        types: {amt: 'float64', w: 'int64', d: 'int64', c: 'int64'},
      },
      {
        sql: "INSERT INTO history (warehouse_id, district_id, history_id, customer_id, date, amount, data) VALUES (@w, @d, @h, @c, @dt, @amt, 'history')",
        params: {
          w: warehouseId,
          d: districtId,
          h: uuidv4(),
          c: customerId,
          dt: new Date(),
          amt: Spanner.float(amount),
        },
        types: {
          w: 'int64',
          d: 'int64',
          h: 'string',
          c: 'int64',
          dt: 'timestamp',
          amt: 'float64',
        },
      },
    ];
    await transaction.batchUpdate(stmts);
    await transaction.commit();
  });
}

export async function executeOrderStatus(
  database: Database,
  scaleFactor: number
): Promise<void> {
  const warehouseId = Math.floor(Math.random() * scaleFactor) + 1;
  const districtId = Math.floor(Math.random() * 10) + 1;
  const customerId = Math.floor(Math.random() * 3000) + 1;

  const [snapshot] = await database.getSnapshot();
  try {
    const custQuery = {
      sql: 'SELECT balance, first_name, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c',
      params: {w: warehouseId, d: districtId, c: customerId},
      types: {w: 'int64', d: 'int64', c: 'int64'},
    };
    const [custRows] = await snapshot.run(custQuery);
    let balance = 0.0;
    let firstName = '';
    let lastName = '';
    if (custRows.length > 0) {
      const cRow = custRows[0].toJSON();
      balance = Number(cRow.balance);
      firstName = String(cRow.first_name);
      lastName = String(cRow.last_name);
    }

    const orderQuery = {
      sql: 'SELECT order_id FROM orders WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c ORDER BY order_id DESC LIMIT 1',
      params: {w: warehouseId, d: districtId, c: customerId},
      types: {w: 'int64', d: 'int64', c: 'int64'},
    };
    const [ordRows] = await snapshot.run(orderQuery);

    if (ordRows.length > 0) {
      const oRow = ordRows[0].toJSON();
      const orderId = Number(oRow.order_id);
      const lineQuery = {
        sql: 'SELECT order_line_id, item_id, quantity, amount FROM order_line WHERE warehouse_id = @w AND district_id = @d AND order_id = @o',
        params: {w: warehouseId, d: districtId, o: orderId},
        types: {w: 'int64', d: 'int64', o: 'int64'},
      };
      const [lineRows] = await snapshot.run(lineQuery);
      for (const line of lineRows) {
        const lRow = line.toJSON();
        const olId = Number(lRow.order_line_id);
        const itemId = Number(lRow.item_id);
        const qty = Number(lRow.quantity);
        const amt = Number(lRow.amount);
      }
    }
  } finally {
    snapshot.end();
  }
}

export async function executeDelivery(
  database: Database,
  scaleFactor: number
): Promise<void> {
  const warehouseId = Math.floor(Math.random() * scaleFactor) + 1;
  const carrierId = Math.floor(Math.random() * 10) + 1;

  await database.runTransactionAsync(async transaction => {
    const batchStatements: any[] = [];
    for (let districtId = 1; districtId <= 10; districtId++) {
      const query = {
        sql: 'SELECT order_id FROM new_orders WHERE warehouse_id = @w AND district_id = @d ORDER BY created_timestamp ASC LIMIT 1',
        params: {w: warehouseId, d: districtId},
        types: {w: 'int64', d: 'int64'},
      };
      const [ordRows] = await transaction.run(query);

      if (ordRows.length > 0) {
        const oRow = ordRows[0].toJSON();
        const orderId = Number(oRow.order_id);
        batchStatements.push({
          sql: 'DELETE FROM new_orders WHERE warehouse_id = @w AND district_id = @d AND order_id = @o',
          params: {w: warehouseId, d: districtId, o: orderId},
          types: {w: 'int64', d: 'int64', o: 'int64'},
        });
        batchStatements.push({
          sql: 'UPDATE orders SET carrier_id = @c WHERE warehouse_id = @w AND district_id = @d AND order_id = @o',
          params: {c: carrierId, w: warehouseId, d: districtId, o: orderId},
          types: {c: 'int64', w: 'int64', d: 'int64', o: 'int64'},
        });
        batchStatements.push({
          sql: 'UPDATE order_line SET delivery_date = @dt WHERE warehouse_id = @w AND district_id = @d AND order_id = @o',
          params: {dt: new Date(), w: warehouseId, d: districtId, o: orderId},
          types: {dt: 'timestamp', w: 'int64', d: 'int64', o: 'int64'},
        });
      }
    }
    if (batchStatements.length > 0) {
      await transaction.batchUpdate(batchStatements);
    }
    await transaction.commit();
  });
}

export async function executeStockLevel(
  database: Database,
  scaleFactor: number
): Promise<void> {
  const warehouseId = Math.floor(Math.random() * scaleFactor) + 1;
  const districtId = Math.floor(Math.random() * 10) + 1;
  const threshold = Math.floor(Math.random() * 6) + 15;

  const [snapshot] = await database.getSnapshot();
  try {
    const distQuery = {
      sql: 'SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d',
      params: {w: warehouseId, d: districtId},
      types: {w: 'int64', d: 'int64'},
    };
    const [distRows] = await snapshot.run(distQuery);

    if (distRows.length > 0) {
      const dRow = distRows[0].toJSON();
      const nextOrderId = Number(dRow.next_order_id);
      const minOrderId = Math.max(1, nextOrderId - 20);

      const stockQuery = {
        sql: 'SELECT COUNT(DISTINCT s.item_id) FROM order_line ol JOIN stock s ON s.warehouse_id = ol.warehouse_id AND s.item_id = ol.item_id WHERE ol.warehouse_id = @w AND ol.district_id = @d AND ol.order_id >= @minOrderId AND ol.order_id < @nextOrderId AND s.quantity < @threshold',
        params: {
          w: warehouseId,
          d: districtId,
          minOrderId,
          nextOrderId,
          threshold,
        },
        types: {
          w: 'int64',
          d: 'int64',
          minOrderId: 'int64',
          nextOrderId: 'int64',
          threshold: 'int64',
        },
      };
      await snapshot.run(stockQuery);
    }
  } finally {
    snapshot.end();
  }
}
