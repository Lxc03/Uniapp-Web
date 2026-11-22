# 用锁文件安装 UniApp 依赖，再产出 H5 静态资源。
FROM node:22-bookworm-slim AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN node --version && npm --version && npm ci --no-audit --no-fund

COPY index.html vite.config.js ./
COPY src ./src
RUN npm run build:h5

# Nginx 同时托管 H5 页面和同源 API 代理。
FROM nginx:1.28-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/build/h5/ /usr/share/nginx/html/
EXPOSE 80
