import { Spanner, SpannerOptions } from "@google-cloud/spanner";

/**
 * Creates a strongly-configured Spanner client instance.
 */
export function createSpannerClient(projectId: string, host?: string): Spanner {
  const options: SpannerOptions = {
    projectId: projectId,
  };

  if (host) {
    let endpoint = host;
    // Strip http:// or https:// prefixes if they exist, as gRPC apiEndpoint expects host:port
    if (endpoint.startsWith("http://")) {
      endpoint = endpoint.substring(7);
    } else if (endpoint.startsWith("https://")) {
      endpoint = endpoint.substring(8);
    }
    options.apiEndpoint = endpoint;

    // If connecting to a local endpoint, disable SSL/authentication via environment or standard custom channel settings
    if (endpoint.startsWith("localhost:") || endpoint.startsWith("127.0.0.1:")) {
      // The Node client library handles this automatically if SPANNER_EMULATOR_HOST is set.
      // Otherwise, passing custom endpoint options or setting serviceName is possible.
    }
  }

  return new Spanner(options);
}
