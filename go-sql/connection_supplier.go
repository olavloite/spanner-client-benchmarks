package main

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"os"
	"strconv"
	"strings"

	_ "github.com/googleapis/go-sql-spanner"
)

type ConnectionInitializer func(ctx context.Context, connection *sql.Conn) error

type ConnectionSupplier struct {
	database    *sql.DB
	initializer ConnectionInitializer
}

func NewConnectionSupplier(database *sql.DB, initializer ConnectionInitializer) *ConnectionSupplier {
	return &ConnectionSupplier{
		database:    database,
		initializer: initializer,
	}
}

func (supplier *ConnectionSupplier) CreateConnection(ctx context.Context) (*sql.Conn, error) {
	connection, err := supplier.database.Conn(ctx)
	if err != nil {
		return nil, err
	}
	if supplier.initializer != nil {
		if err := supplier.initializer(ctx, connection); err != nil {
			_ = connection.Close()
			return nil, err
		}
	}
	return connection, nil
}

func (supplier *ConnectionSupplier) Database() *sql.DB {
	return supplier.database
}

func (supplier *ConnectionSupplier) Close() error {
	if supplier.database != nil {
		return supplier.database.Close()
	}
	return nil
}

func createDatabase(project, instance, database, host string, poolSize int) (*sql.DB, error) {
	if host == "" {
		if emulatorHost := os.Getenv("SPANNER_EMULATOR_HOST"); emulatorHost != "" {
			host = emulatorHost
		}
	}
	dataSourceName := fmt.Sprintf("projects/%s/instances/%s/databases/%s", project, instance, database)
	if host != "" {
		dataSourceName = fmt.Sprintf("%s/projects/%s/instances/%s/databases/%s?usePlainText=true", host, project, instance, database)
	}

	numChannelsString := os.Getenv("SPANNER_NUM_CHANNELS")
	if numChannelsString != "" {
		if numChannels, err := strconv.Atoi(numChannelsString); err == nil {
			log.Printf("Configured Spanner Go database/sql driver with %d channels.", numChannels)
			if strings.Contains(dataSourceName, "?") {
				dataSourceName = fmt.Sprintf("%s;numChannels=%d", dataSourceName, numChannels)
			} else {
				dataSourceName = fmt.Sprintf("%s?numChannels=%d", dataSourceName, numChannels)
			}
		}
	}
	if strings.EqualFold(os.Getenv("GOOGLE_SPANNER_ENABLE_DIRECT_ACCESS"), "true") {
		log.Println("Configured Spanner Go database/sql driver with DirectPath (direct access) enabled.")
	}

	db, err := sql.Open("spanner", dataSourceName)
	if err != nil {
		return nil, err
	}

	maximumConnections := poolSize + 10
	if maximumConnections < 20 {
		maximumConnections = 20
	}
	db.SetMaxOpenConns(maximumConnections)
	db.SetMaxIdleConns(maximumConnections)

	return db, nil
}
