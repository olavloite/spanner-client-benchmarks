FROM node:18-slim
WORKDIR /app
COPY package.json .
RUN npm install --only=production
COPY index.js .
ENTRYPOINT ["node", "index.js"]
