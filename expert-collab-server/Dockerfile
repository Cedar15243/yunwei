FROM node:24-alpine

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --include=dev

COPY src ./src
COPY tsconfig.json ./

ENV NODE_ENV=production
EXPOSE 8787

CMD ["npm", "run", "start"]
