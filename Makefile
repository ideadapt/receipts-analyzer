.PHONY: server client client-dev

server:
	cd receipt-summary-server && ./gradlew runFatJar

client:
	cd client && npm run dist

client-dev:
	cd client && npm run dev
