FROM node:20-alpine

RUN apk add --no-cache python3 make g++

WORKDIR /app

COPY package*.json ./
RUN npm install

COPY src ./src

EXPOSE 8080

CMD ["node", "src/index.js"]
