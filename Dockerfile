FROM node:18-alpine

WORKDIR /app

# Copy package files
COPY package*.json ./
RUN npm install

# Copy source code
COPY server.js .

EXPOSE 3000

CMD ["node", "server.js"]
