import datetime
import random
import uuid

from google.cloud import spanner
from google.cloud.spanner_v1.database import Database
from google.cloud.spanner_v1.transaction import Transaction


def execute_new_order(
    database: Database, scale_factor: int, total_items: int, extended: bool = False
) -> None:
    warehouse_id = random.randint(1, scale_factor)
    district_id = random.randint(1, 10)
    customer_id = random.randint(1, 3000)
    num_items = random.randint(5, 15)

    item_ids = [random.randint(1, total_items) for _ in range(num_items)]
    quantities = [random.randint(1, 10) for _ in range(num_items)]

    def _cb(transaction: Transaction):
        results = transaction.execute_sql(
            "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d FOR UPDATE",
            params={"w": warehouse_id, "d": district_id},
            param_types={
                "w": spanner.param_types.INT64,
                "d": spanner.param_types.INT64,
            },
            request_options={"request_tag": "new_order_get_district"},
        )
        next_order_id = 1000
        for row in results:
            next_order_id = row[0]

        cust_results = transaction.execute_sql(
            "SELECT discount, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c",
            params={"w": warehouse_id, "d": district_id, "c": customer_id},
            param_types={
                "w": spanner.param_types.INT64,
                "d": spanner.param_types.INT64,
                "c": spanner.param_types.INT64,
            },
            request_options={"request_tag": "new_order_get_customer"},
        )
        for cust_row in cust_results:
            discount = cust_row[0]
            last_name = cust_row[1]

        now = datetime.datetime.now(datetime.timezone.utc)
        batch_statements = [
            (
                "UPDATE district SET next_order_id = @next WHERE warehouse_id = @w AND district_id = @d",
                {"next": next_order_id + 1, "w": warehouse_id, "d": district_id},
                {
                    "next": spanner.param_types.INT64,
                    "w": spanner.param_types.INT64,
                    "d": spanner.param_types.INT64,
                },
            ),
            (
                "INSERT INTO orders (warehouse_id, district_id, order_id, customer_id, entry_date, item_count, all_local) VALUES (@w, @d, @o, @c, @dt, @cnt, 1)",
                {
                    "w": warehouse_id,
                    "d": district_id,
                    "o": next_order_id,
                    "c": customer_id,
                    "dt": now,
                    "cnt": num_items,
                },
                {
                    "w": spanner.param_types.INT64,
                    "d": spanner.param_types.INT64,
                    "o": spanner.param_types.INT64,
                    "c": spanner.param_types.INT64,
                    "dt": spanner.param_types.TIMESTAMP,
                    "cnt": spanner.param_types.INT64,
                },
            ),
            (
                "INSERT INTO new_orders (warehouse_id, district_id, order_id, created_timestamp) VALUES (@w, @d, @o, @dt)",
                {"w": warehouse_id, "d": district_id, "o": next_order_id, "dt": now},
                {
                    "w": spanner.param_types.INT64,
                    "d": spanner.param_types.INT64,
                    "o": spanner.param_types.INT64,
                    "dt": spanner.param_types.TIMESTAMP,
                },
            ),
        ]

        for i in range(num_items):
            batch_statements.append(
                (
                    "INSERT INTO order_line (warehouse_id, district_id, order_id, order_line_id, item_id, quantity, amount, dist_info) VALUES (@w, @d, @o, @ol, @i, @qty, @amt, 'distinfo')",
                    {
                        "w": warehouse_id,
                        "d": district_id,
                        "o": next_order_id,
                        "ol": i + 1,
                        "i": item_ids[i],
                        "qty": quantities[i],
                        "amt": 25.0,
                    },
                    {
                        "w": spanner.param_types.INT64,
                        "d": spanner.param_types.INT64,
                        "o": spanner.param_types.INT64,
                        "ol": spanner.param_types.INT64,
                        "i": spanner.param_types.INT64,
                        "qty": spanner.param_types.INT64,
                        "amt": spanner.param_types.FLOAT64,
                    },
                )
            )
            batch_statements.append(
                (
                    "UPDATE stock SET quantity = quantity - @qty, order_count = order_count + 1 WHERE warehouse_id = @w AND item_id = @i",
                    {"qty": quantities[i], "w": warehouse_id, "i": item_ids[i]},
                    {
                        "qty": spanner.param_types.INT64,
                        "w": spanner.param_types.INT64,
                        "i": spanner.param_types.INT64,
                    },
                )
            )
        transaction.batch_update(
            batch_statements, request_options={"request_tag": "new_order_batch_dml"}
        )

    database.run_in_transaction(
        _cb,
        transaction_tag="new_order",
        commit_request_options={"request_tag": "new_order_commit"},
    )


def execute_payment(
    database: Database, scale_factor: int, extended: bool = False
) -> None:
    warehouse_id = random.randint(1, scale_factor)
    district_id = random.randint(1, 10)
    customer_id = random.randint(1, 3000)
    amount = random.uniform(1.0, 5000.0)

    def _cb(transaction: Transaction):
        batch_statements = [
            (
                "UPDATE warehouse SET ytd = ytd + @amt WHERE warehouse_id = @w",
                {"amt": amount, "w": warehouse_id},
                {"amt": spanner.param_types.FLOAT64, "w": spanner.param_types.INT64},
            ),
            (
                "UPDATE district SET ytd = ytd + @amt WHERE warehouse_id = @w AND district_id = @d",
                {"amt": amount, "w": warehouse_id, "d": district_id},
                {
                    "amt": spanner.param_types.FLOAT64,
                    "w": spanner.param_types.INT64,
                    "d": spanner.param_types.INT64,
                },
            ),
            (
                "UPDATE customer SET balance = balance - @amt, ytd_payment = ytd_payment + @amt, payment_count = payment_count + 1 WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c",
                {"amt": amount, "w": warehouse_id, "d": district_id, "c": customer_id},
                {
                    "amt": spanner.param_types.FLOAT64,
                    "w": spanner.param_types.INT64,
                    "d": spanner.param_types.INT64,
                    "c": spanner.param_types.INT64,
                },
            ),
            (
                "INSERT INTO history (warehouse_id, district_id, history_id, customer_id, date, amount, data) VALUES (@w, @d, @h, @c, @dt, @amt, 'history')",
                {
                    "w": warehouse_id,
                    "d": district_id,
                    "h": str(uuid.uuid4()),
                    "c": customer_id,
                    "dt": datetime.datetime.now(datetime.timezone.utc),
                    "amt": amount,
                },
                {
                    "w": spanner.param_types.INT64,
                    "d": spanner.param_types.INT64,
                    "h": spanner.param_types.STRING,
                    "c": spanner.param_types.INT64,
                    "dt": spanner.param_types.TIMESTAMP,
                    "amt": spanner.param_types.FLOAT64,
                },
            ),
        ]
        transaction.batch_update(
            batch_statements, request_options={"request_tag": "payment_batch_dml"}
        )

    database.run_in_transaction(
        _cb,
        transaction_tag="payment",
        commit_request_options={"request_tag": "payment_commit"},
    )


def execute_order_status(
    database: Database, scale_factor: int, extended: bool = False
) -> None:
    warehouse_id = random.randint(1, scale_factor)
    district_id = random.randint(1, 10)
    customer_id = random.randint(1, 3000)

    snapshot_args = {"multi_use": True}
    if extended:
        snapshot_args["exact_staleness"] = datetime.timedelta(seconds=15)

    with database.snapshot(**snapshot_args) as snapshot:
        cust_res = snapshot.execute_sql(
            "SELECT balance, first_name, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c",
            params={"w": warehouse_id, "d": district_id, "c": customer_id},
            param_types={
                "w": spanner.param_types.INT64,
                "d": spanner.param_types.INT64,
                "c": spanner.param_types.INT64,
            },
            request_options={"request_tag": "order_status_customer"},
        )
        for c_row in cust_res:
            balance = c_row[0]
            first_name = c_row[1]
            last_name = c_row[2]

        order_res = snapshot.execute_sql(
            "SELECT order_id FROM orders WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c ORDER BY order_id DESC LIMIT 1",
            params={"w": warehouse_id, "d": district_id, "c": customer_id},
            param_types={
                "w": spanner.param_types.INT64,
                "d": spanner.param_types.INT64,
                "c": spanner.param_types.INT64,
            },
            request_options={"request_tag": "order_status_order"},
        )
        order_id = -1
        for row in order_res:
            order_id = row[0]

        if order_id != -1:
            line_res = snapshot.execute_sql(
                "SELECT order_line_id, item_id, quantity, amount FROM order_line WHERE warehouse_id = @w AND district_id = @d AND order_id = @o",
                params={"w": warehouse_id, "d": district_id, "o": order_id},
                param_types={
                    "w": spanner.param_types.INT64,
                    "d": spanner.param_types.INT64,
                    "o": spanner.param_types.INT64,
                },
                request_options={"request_tag": "order_status_order_lines"},
            )
            for l_row in line_res:
                ol_id = l_row[0]
                i_id = l_row[1]
                qty = l_row[2]
                amt = l_row[3]


def execute_delivery(
    database: Database, scale_factor: int, extended: bool = False
) -> None:
    warehouse_id = random.randint(1, scale_factor)
    carrier_id = random.randint(1, 10)

    def _cb(transaction: Transaction):
        batch_statements = []
        for district_id in range(1, 11):
            new_orders_res = transaction.execute_sql(
                "SELECT order_id FROM new_orders WHERE warehouse_id = @w AND district_id = @d ORDER BY created_timestamp ASC LIMIT 1 FOR UPDATE",
                params={"w": warehouse_id, "d": district_id},
                param_types={
                    "w": spanner.param_types.INT64,
                    "d": spanner.param_types.INT64,
                },
                request_options={"request_tag": "delivery_oldest_new_order"},
            )
            order_id = -1
            for row in new_orders_res:
                order_id = row[0]

            if order_id != -1:
                batch_statements.append(
                    (
                        "DELETE FROM new_orders WHERE warehouse_id = @w AND district_id = @d AND order_id = @o",
                        {"w": warehouse_id, "d": district_id, "o": order_id},
                        {
                            "w": spanner.param_types.INT64,
                            "d": spanner.param_types.INT64,
                            "o": spanner.param_types.INT64,
                        },
                    )
                )
                batch_statements.append(
                    (
                        "UPDATE orders SET carrier_id = @c WHERE warehouse_id = @w AND district_id = @d AND order_id = @o",
                        {
                            "c": carrier_id,
                            "w": warehouse_id,
                            "d": district_id,
                            "o": order_id,
                        },
                        {
                            "c": spanner.param_types.INT64,
                            "w": spanner.param_types.INT64,
                            "d": spanner.param_types.INT64,
                            "o": spanner.param_types.INT64,
                        },
                    )
                )
                batch_statements.append(
                    (
                        "UPDATE order_line SET delivery_date = @dt WHERE warehouse_id = @w AND district_id = @d AND order_id = @o",
                        {
                            "dt": datetime.datetime.now(datetime.timezone.utc),
                            "w": warehouse_id,
                            "d": district_id,
                            "o": order_id,
                        },
                        {
                            "dt": spanner.param_types.TIMESTAMP,
                            "w": spanner.param_types.INT64,
                            "d": spanner.param_types.INT64,
                            "o": spanner.param_types.INT64,
                        },
                    )
                )
        if batch_statements:
            transaction.batch_update(
                batch_statements, request_options={"request_tag": "delivery_batch_dml"}
            )

    database.run_in_transaction(
        _cb,
        transaction_tag="delivery",
        commit_request_options={"request_tag": "delivery_commit"},
    )


def execute_stock_level(
    database: Database, scale_factor: int, extended: bool = False
) -> None:
    warehouse_id = random.randint(1, scale_factor)
    district_id = random.randint(1, 10)
    threshold = random.randint(15, 20)

    with database.snapshot(multi_use=True) as snapshot:
        dist_res = snapshot.execute_sql(
            "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d",
            params={"w": warehouse_id, "d": district_id},
            param_types={
                "w": spanner.param_types.INT64,
                "d": spanner.param_types.INT64,
            },
            request_options={"request_tag": "stock_level_district"},
        )
        next_order_id = -1
        for row in dist_res:
            next_order_id = row[0]

        if next_order_id != -1:
            min_order_id = max(1, next_order_id - 20)
            stock_res = snapshot.execute_sql(
                "SELECT COUNT(DISTINCT s.item_id) FROM order_line ol JOIN stock s ON s.warehouse_id = ol.warehouse_id AND s.item_id = ol.item_id WHERE ol.warehouse_id = @w AND ol.district_id = @d AND ol.order_id >= @min_order_id AND ol.order_id < @next_order_id AND s.quantity < @threshold",
                params={
                    "w": warehouse_id,
                    "d": district_id,
                    "min_order_id": min_order_id,
                    "next_order_id": next_order_id,
                    "threshold": threshold,
                },
                param_types={
                    "w": spanner.param_types.INT64,
                    "d": spanner.param_types.INT64,
                    "min_order_id": spanner.param_types.INT64,
                    "next_order_id": spanner.param_types.INT64,
                    "threshold": spanner.param_types.INT64,
                },
                request_options={"request_tag": "stock_level_stock"},
            )
            for _ in stock_res:
                pass


def execute_new_order_mutations(
    database: Database, scale_factor: int, total_items: int
) -> None:
    warehouse_id = random.randint(1, scale_factor)
    district_id = random.randint(1, 10)
    customer_id = random.randint(1, 3000)
    num_items = random.randint(5, 15)

    item_ids = [random.randint(1, total_items) for _ in range(num_items)]
    quantities = [random.randint(1, 10) for _ in range(num_items)]

    def _cb(transaction: Transaction):
        results = transaction.execute_sql(
            "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d",
            params={"w": warehouse_id, "d": district_id},
            param_types={
                "w": spanner.param_types.INT64,
                "d": spanner.param_types.INT64,
            },
            request_options={"request_tag": "new_order_mutations_get_district"},
        )
        next_order_id = 1000
        for row in results:
            next_order_id = row[0]

        cust_results = transaction.execute_sql(
            "SELECT discount, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c",
            params={"w": warehouse_id, "d": district_id, "c": customer_id},
            param_types={
                "w": spanner.param_types.INT64,
                "d": spanner.param_types.INT64,
                "c": spanner.param_types.INT64,
            },
            request_options={"request_tag": "new_order_mutations_get_customer"},
        )
        for cust_row in cust_results:
            pass

        stock_results = transaction.execute_sql(
            "SELECT item_id, quantity FROM stock WHERE warehouse_id = @w AND item_id IN UNNEST(@items)",
            params={"w": warehouse_id, "items": item_ids},
            param_types={
                "w": spanner.param_types.INT64,
                "items": spanner.param_types.Array(spanner.param_types.INT64),
            },
            request_options={"request_tag": "new_order_mutations_get_stock"},
        )
        stock_map = {}
        for row in stock_results:
            stock_map[row[0]] = row[1]

        now = datetime.datetime.now(datetime.timezone.utc)

        transaction.update(
            table="district",
            columns=["warehouse_id", "district_id", "next_order_id"],
            values=[(warehouse_id, district_id, next_order_id + 1)],
        )

        transaction.insert(
            table="orders",
            columns=[
                "warehouse_id",
                "district_id",
                "order_id",
                "customer_id",
                "entry_date",
                "item_count",
                "all_local",
            ],
            values=[
                (
                    warehouse_id,
                    district_id,
                    next_order_id,
                    customer_id,
                    now,
                    num_items,
                    1,
                )
            ],
        )

        transaction.insert(
            table="new_orders",
            columns=["warehouse_id", "district_id", "order_id", "created_timestamp"],
            values=[(warehouse_id, district_id, next_order_id, now)],
        )

        order_line_values = []
        for i in range(num_items):
            item_id = item_ids[i]
            qty = quantities[i]
            current_qty = stock_map.get(item_id, 10)
            new_qty = current_qty - qty
            if new_qty < 10:
                new_qty += 91

            order_line_values.append(
                (
                    warehouse_id,
                    district_id,
                    next_order_id,
                    i + 1,
                    item_id,
                    qty,
                    25.0,
                    "distinfo",
                )
            )

            transaction.update(
                table="stock",
                columns=["warehouse_id", "item_id", "quantity"],
                values=[(warehouse_id, item_id, new_qty)],
            )

        transaction.insert(
            table="order_line",
            columns=[
                "warehouse_id",
                "district_id",
                "order_id",
                "order_line_id",
                "item_id",
                "quantity",
                "amount",
                "dist_info",
            ],
            values=order_line_values,
        )

    database.run_in_transaction(
        _cb,
        transaction_tag="new_order_mutations",
        commit_request_options={"request_tag": "new_order_mutations_commit"},
    )


def execute_payment_mutations_direct(database: Database, scale_factor: int) -> None:
    warehouse_id = random.randint(1, scale_factor)
    district_id = random.randint(1, 10)
    customer_id = random.randint(1, 3000)
    amount = random.uniform(1.0, 5000.0)

    def _cb(transaction: Transaction):
        batch_statements = [
            (
                "UPDATE warehouse SET ytd = ytd + @amt WHERE warehouse_id = @w",
                {"amt": amount, "w": warehouse_id},
                {"amt": spanner.param_types.FLOAT64, "w": spanner.param_types.INT64},
            ),
            (
                "UPDATE district SET ytd = ytd + @amt WHERE warehouse_id = @w AND district_id = @d",
                {"amt": amount, "w": warehouse_id, "d": district_id},
                {
                    "amt": spanner.param_types.FLOAT64,
                    "w": spanner.param_types.INT64,
                    "d": spanner.param_types.INT64,
                },
            ),
            (
                "UPDATE customer SET balance = balance - @amt, ytd_payment = ytd_payment + @amt, payment_count = payment_count + 1 WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c",
                {"amt": amount, "w": warehouse_id, "d": district_id, "c": customer_id},
                {
                    "amt": spanner.param_types.FLOAT64,
                    "w": spanner.param_types.INT64,
                    "d": spanner.param_types.INT64,
                    "c": spanner.param_types.INT64,
                },
            ),
        ]
        transaction.batch_update(
            batch_statements,
            request_options={"request_tag": "payment_mutations_direct_batch_dml"},
        )

    database.run_in_transaction(
        _cb,
        transaction_tag="payment_mutations_direct",
        commit_request_options={"request_tag": "payment_mutations_direct_commit"},
    )

    with database.batch(
        request_options={"request_tag": "payment_mutations_direct_write"}
    ) as batch:
        batch.insert(
            table="history",
            columns=[
                "warehouse_id",
                "district_id",
                "history_id",
                "customer_id",
                "date",
                "amount",
                "data",
            ],
            values=[
                (
                    warehouse_id,
                    district_id,
                    str(uuid.uuid4()),
                    customer_id,
                    datetime.datetime.now(datetime.timezone.utc),
                    amount,
                    "history",
                )
            ],
        )


def execute_order_status_reads(database: Database, scale_factor: int) -> None:
    warehouse_id = random.randint(1, scale_factor)
    district_id = random.randint(1, 10)
    customer_id = random.randint(1, 3000)

    with database.snapshot(
        exact_staleness=datetime.timedelta(seconds=15), multi_use=True
    ) as snapshot:
        keyset = spanner.KeySet(keys=[(warehouse_id, district_id, customer_id)])
        cust_res = snapshot.read(
            table="customer",
            columns=["balance", "first_name", "last_name"],
            keyset=keyset,
            request_options={"request_tag": "order_status_reads_customer"},
        )
        for row in cust_res:
            balance = row[0]
            first_name = row[1]
            last_name = row[2]

        order_res = snapshot.execute_sql(
            "SELECT order_id FROM orders WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c ORDER BY order_id DESC LIMIT 1",
            params={"w": warehouse_id, "d": district_id, "c": customer_id},
            param_types={
                "w": spanner.param_types.INT64,
                "d": spanner.param_types.INT64,
                "c": spanner.param_types.INT64,
            },
            request_options={"request_tag": "order_status_reads_order"},
        )
        order_id = -1
        for row in order_res:
            order_id = row[0]

        if order_id != -1:
            key_range = spanner.KeyRange(
                start_closed=[warehouse_id, district_id, order_id],
                end_open=[warehouse_id, district_id, order_id + 1],
            )
            keyset = spanner.KeySet(ranges=[key_range])
            line_res = snapshot.read(
                table="order_line",
                columns=["order_line_id", "item_id", "quantity", "amount"],
                keyset=keyset,
                request_options={"request_tag": "order_status_reads_order_lines"},
            )
            for row in line_res:
                ol_id = row[0]
                i_id = row[1]
                qty = row[2]
                amt = row[3]


def execute_stock_level_partitioned(database: Database, scale_factor: int) -> None:
    warehouse_id = random.randint(1, scale_factor)
    district_id = random.randint(1, 10)
    threshold = random.randint(15, 20)

    snapshot = database.batch_snapshot(exact_staleness=datetime.timedelta(seconds=15))
    try:
        dist_res = snapshot.execute_sql(
            "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d",
            params={"w": warehouse_id, "d": district_id},
            param_types={
                "w": spanner.param_types.INT64,
                "d": spanner.param_types.INT64,
            },
            request_options={"request_tag": "stock_level_partitioned_district"},
        )
        next_order_id = -1
        for row in dist_res:
            next_order_id = row[0]

        if next_order_id != -1:
            min_order_id = max(1, next_order_id - 20)

            sql = (
                "SELECT DISTINCT s.item_id FROM order_line ol "
                "JOIN stock s ON s.warehouse_id = ol.warehouse_id AND s.item_id = ol.item_id "
                "WHERE ol.warehouse_id = @w AND ol.district_id = @d "
                "AND ol.order_id >= @min_order_id AND ol.order_id < @next_order_id "
                "AND s.quantity < @threshold"
            )
            params = {
                "w": warehouse_id,
                "d": district_id,
                "min_order_id": min_order_id,
                "next_order_id": next_order_id,
                "threshold": threshold,
            }
            param_types = {
                "w": spanner.param_types.INT64,
                "d": spanner.param_types.INT64,
                "min_order_id": spanner.param_types.INT64,
                "next_order_id": spanner.param_types.INT64,
                "threshold": spanner.param_types.INT64,
            }

            batches = list(
                snapshot.generate_query_batches(
                    sql=sql,
                    params=params,
                    param_types=param_types,
                )
            )

            for batch in batches:
                batch["query"]["request_options"] = {
                    "request_tag": "stock_level_partitioned_stock"
                }

            from concurrent.futures import ThreadPoolExecutor

            unique_item_ids = set()
            with ThreadPoolExecutor(max_workers=len(batches) or 1) as executor:

                def process_batch(b):
                    rows = snapshot.process_query_batch(b)
                    items = []
                    for r in rows:
                        items.append(r[0])
                    return items

                results = executor.map(process_batch, batches)
                for items in results:
                    unique_item_ids.update(items)

            distinct_count = len(unique_item_ids)

    finally:
        snapshot.close()
