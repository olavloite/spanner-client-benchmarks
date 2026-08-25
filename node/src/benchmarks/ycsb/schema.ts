import {Database} from '@google-cloud/spanner';

/**
 * Generates the DDL statements for creating the YCSB table in Cloud Spanner.
 */
export function generateSchemaDdl(
  tableName: string,
  fieldCount: number,
): string {
  const fieldDefs: string[] = [];
  for (let i = 0; i < fieldCount; i++) {
    fieldDefs.push(`    field${i} STRING(MAX)`);
  }

  return `CREATE TABLE IF NOT EXISTS ${tableName} (\n    id STRING(MAX),\n${fieldDefs.join(',\n')}\n) PRIMARY KEY(id)`;
}

/**
 * Checks if the specified table already exists in the Spanner database.
 */
export async function tableExists(
  database: Database,
  tableName: string,
): Promise<boolean> {
  try {
    const [rows] = await database.run({
      sql: "SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '' AND TABLE_NAME = @tableName",
      params: {tableName},
    });
    return rows.length > 0;
  } catch {
    return false;
  }
}

/**
 * Initializes the YCSB database schema by creating the target table if it does not already exist.
 */
export async function initSchema(
  database: Database,
  tableName: string,
  fieldCount: number,
  skipSchema = false,
): Promise<void> {
  if (skipSchema) {
    console.log('Skipping schema creation (--skip-schema flag specified).');
    return;
  }

  const exists = await tableExists(database, tableName);
  if (exists) {
    console.log(`Table '${tableName}' already exists. Skipping DDL creation.`);
    return;
  }

  console.log(`Creating table '${tableName}'...`);
  const ddl = generateSchemaDdl(tableName, fieldCount);
  const [operation] = await database.updateSchema([ddl]);
  await operation.promise();
  console.log(`Table '${tableName}' created successfully.`);
}
