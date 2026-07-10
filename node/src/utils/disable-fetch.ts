process.on('uncaughtException', (err) => {
  console.error('FATAL UNCAUGHT EXCEPTION ON STARTUP:', err);
  // Delay exit by 5 seconds to ensure Fluent Bit has time to flush logs to Cloud Logging
  setTimeout(() => process.exit(1), 5000);
});
process.on('unhandledRejection', (reason) => {
  console.error('FATAL UNHANDLED REJECTION ON STARTUP:', reason);
  // Delay exit by 5 seconds to ensure Fluent Bit has time to flush logs to Cloud Logging
  setTimeout(() => process.exit(1), 5000);
});

// Disable global fetch to force standard HTTPS socket fallback in GCP exporters
// @ts-ignore
delete (globalThis as any).fetch;
console.log('Disabled global fetch API successfully.');

// Disable keep-alive on the global HTTPS agent to prevent GCE connection resets (Premature close)
import * as https from 'https';
if (https.globalAgent) {
  (https.globalAgent as any).keepAlive = false;
  if ((https.globalAgent as any).options) {
    (https.globalAgent as any).options.keepAlive = false;
  }
}
console.log('Disabled global HTTPS agent keep-alive successfully.');
