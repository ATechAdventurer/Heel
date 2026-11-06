FROM node:24-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force
COPY server.mjs relay.mjs ./
USER node
ENV HOST=127.0.0.1 PORT=8787
CMD ["node", "server.mjs"]
