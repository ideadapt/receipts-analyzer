# Receipts Analyzer

Let AI analyze and categorize the line items on your receipts.
The result is a CSV file that can be used to create insightful reports.

## Setup

TODO

## Feature Ideas

- Specialized own UI with statistics (works out of the box today by copy&paste analysis result CSV to any spreadsheet
  app)
- Connect Migros API (unofficial scrapers exist)
- Basic UI to upload receipts (MVP: link to nextcloud share UI) and download analysis result CSV

## TODO

- remove duplicate line items
- ingest migros csv export
- connect migros via API wrapper
- connect client to kotlin server. remove deno server
- deploy docker to fly.io.
- serve client assets within server (also in dev env?).
- deal with OpenAI quota errors (back off / retry). 
