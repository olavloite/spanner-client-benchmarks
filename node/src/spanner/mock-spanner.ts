import * as grpc from '@grpc/grpc-js';
import * as protoLoader from '@grpc/proto-loader';
import * as path from 'path';

export class MockSpannerServer {
  private server: grpc.Server;
  private port = 0;
  private requests: any[] = [];
  private results: Map<string, any> = new Map();
  private sessionCounter = 0;
  private transactionCounter = 0;

  constructor() {
    this.server = new grpc.Server();
  }

  public start(): Promise<number> {
    const PROTO_PATH = path.resolve(
      process.cwd(),
      'node_modules/@google-cloud/spanner/build/protos/google/spanner/v1/spanner.proto',
    );
    const packageDefinition = protoLoader.loadSync(PROTO_PATH, {
      keepCase: true,
      longs: String,
      enums: String,
      defaults: true,
      oneofs: true,
      includeDirs: [
        path.resolve(
          process.cwd(),
          'node_modules/@google-cloud/spanner/build/protos',
        ),
        path.resolve(process.cwd(), 'node_modules/google-gax/build/protos'),
      ],
    });
    const spannerProto = (grpc.loadPackageDefinition(packageDefinition) as any)
      .google.spanner.v1;

    this.server.addService(spannerProto.Spanner.service, {
      CreateSession: this.createSession.bind(this),
      BatchCreateSessions: this.batchCreateSessions.bind(this),
      GetSession: this.getSession.bind(this),
      DeleteSession: this.deleteSession.bind(this),
      ExecuteSql: this.executeSql.bind(this),
      ExecuteStreamingSql: this.executeStreamingSql.bind(this),
      ExecuteBatchDml: this.executeBatchDml.bind(this),
      BeginTransaction: this.beginTransaction.bind(this),
      Commit: this.commit.bind(this),
      Rollback: this.rollback.bind(this),
    });

    return new Promise((resolve, reject) => {
      this.server.bindAsync(
        '127.0.0.1:0',
        grpc.ServerCredentials.createInsecure(),
        (err, port) => {
          if (err) {
            reject(err);
          } else {
            this.port = port;
            this.server.start();
            resolve(port);
          }
        },
      );
    });
  }

  public stop(): Promise<void> {
    return new Promise(resolve => {
      try {
        this.server.forceShutdown();
      } catch (err) {
        console.error('Error during forceShutdown:', err);
      }
      resolve();
    });
  }

  public addResult(sql: string, resultSet: any) {
    this.results.set(sql.toLowerCase().trim(), resultSet);
  }

  public clearResults() {
    this.results.clear();
  }

  public clearRequests() {
    this.requests.length = 0;
  }

  public getRequests(): any[] {
    return this.requests;
  }

  public getPort(): number {
    return this.port;
  }

  private createSession(call: any, callback: any) {
    this.requests.push(call.request);
    this.sessionCounter++;
    const name = `${call.request.database}/sessions/session-${this.sessionCounter}`;
    const session: any = {name};
    if (call.request.session?.multiplexed) {
      session.multiplexed = true;
    }
    callback(null, session);
  }

  private batchCreateSessions(call: any, callback: any) {
    this.requests.push(call.request);
    const sessions: any[] = [];
    for (let i = 0; i < call.request.session_count; i++) {
      this.sessionCounter++;
      const session: any = {
        name: `${call.request.database}/sessions/session-${this.sessionCounter}`,
      };
      if (
        call.request.sessionTemplate?.multiplexed ||
        call.request.session_template?.multiplexed
      ) {
        session.multiplexed = true;
      }
      sessions.push(session);
    }
    callback(null, {session: sessions});
  }

  private getSession(call: any, callback: any) {
    this.requests.push(call.request);
    callback(null, {name: call.request.name});
  }

  private deleteSession(call: any, callback: any) {
    this.requests.push(call.request);
    callback(null, {});
  }

  private beginTransaction(call: any, callback: any) {
    this.requests.push(call.request);
    this.transactionCounter++;
    const id = Buffer.from(`tx-${this.transactionCounter}`).toString('base64');
    callback(null, {id});
  }

  private executeSql(call: any, callback: any) {
    this.requests.push(call.request);
    const sql = call.request.sql;
    const result = this.results.get(sql.toLowerCase().trim());
    if (!result) {
      callback({
        code: grpc.status.NOT_FOUND,
        message: `No result found for SQL: ${sql}`,
      });
      return;
    }

    // Clone result to avoid mutating registered mocks
    const response = JSON.parse(JSON.stringify(result));
    if (call.request.transaction?.begin) {
      this.transactionCounter++;
      const txId = Buffer.from(`tx-${this.transactionCounter}`).toString(
        'base64',
      );
      response.metadata = response.metadata || {};
      response.metadata.transaction = {id: txId};
    }
    callback(null, response);
  }

  private executeStreamingSql(call: any) {
    this.requests.push(call.request);
    const sql = call.request.sql;
    const result = this.results.get(sql.toLowerCase().trim());
    if (!result) {
      call.emit('error', {
        code: grpc.status.NOT_FOUND,
        message: `No result found for SQL: ${sql}`,
      });
      call.end();
      return;
    }

    const fields = result.metadata?.row_type?.fields || [];
    const firstPart: any = {
      metadata: {
        row_type: {fields},
      },
    };

    if (call.request.transaction?.begin) {
      this.transactionCounter++;
      const txId = Buffer.from(`tx-${this.transactionCounter}`).toString(
        'base64',
      );
      firstPart.metadata.transaction = {id: txId};
    }

    call.write(firstPart);

    if (result.rows && result.rows.length > 0) {
      for (const row of result.rows) {
        call.write({
          values: row,
        });
      }
    }

    if (result.stats) {
      call.write({
        stats: result.stats,
      });
    }

    call.end();
  }

  private executeBatchDml(call: any, callback: any) {
    this.requests.push(call.request);
    const resultSets: any[] = [];
    let txId: string | undefined;

    if (call.request.transaction?.begin) {
      this.transactionCounter++;
      txId = Buffer.from(`tx-${this.transactionCounter}`).toString('base64');
    }

    for (const stmt of call.request.statements) {
      const result = this.results.get(stmt.sql.toLowerCase().trim());
      if (!result) {
        callback({
          code: grpc.status.NOT_FOUND,
          message: `No result found for SQL: ${stmt.sql}`,
        });
        return;
      }
      const response = JSON.parse(JSON.stringify(result));
      if (txId) {
        response.metadata = response.metadata || {};
        response.metadata.transaction = {id: txId};
      }
      resultSets.push(response);
    }

    callback(null, {
      result_sets: resultSets,
      status: {code: 0, message: 'OK'},
    });
  }

  private commit(call: any, callback: any) {
    this.requests.push(call.request);
    callback(null, {
      commit_timestamp: {seconds: Math.floor(Date.now() / 1000), nanos: 0},
    });
  }

  private rollback(call: any, callback: any) {
    this.requests.push(call.request);
    callback(null, {});
  }
}
