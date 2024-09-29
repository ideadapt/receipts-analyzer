.PHONY: server server-dev client client-dev

server:
	cd server && deno run --allow-net --allow-env --allow-read ./main.ts

server-dev:
	cd server && denon run --allow-net --allow-env --allow-read ./main.ts

client:
	cd client && npm run dist

client-dev:
	cd client && npm run dev
