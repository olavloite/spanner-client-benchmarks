# Stage 1: Build application against latest Spanner client source
FROM node:20 AS build
WORKDIR /app

# Install git for cloning
RUN apt-get update && apt-get install -y git

# Copy build scripts and sources
COPY build_app.sh .
COPY package.json .
COPY tsconfig.json .
COPY index.ts .
COPY src/ ./src/

# Run the build script (clones google-cloud-node, compiles Spanner, installs it, and compiles TypeScript)
RUN chmod +x build_app.sh && ./build_app.sh

# Stage 2: Production runtime image
FROM node:20-slim
WORKDIR /app

# Copy the compiled JavaScript from the build stage
COPY --from=build /app/dist ./dist
# Copy the node_modules (which contains the compiled local Spanner client and all dependencies)
COPY --from=build /app/node_modules ./node_modules
# Copy package.json
COPY --from=build /app/package.json ./package.json

ENTRYPOINT ["node", "dist/index.js"]
