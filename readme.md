# Receipts Analyzer

Let AI analyze and categorize the line items on your receipts.
The result is a CSV file that can be used to create insightful reports.

## Setup

TODO

## Feature Ideas

- Specialized own UI with statistics (works out of the box today by copy&paste analysis result CSV to any spreadsheet
  app)
- Connect Migros API (unofficial scrapers exist)
- Download analysis result CSV
- Link to receipt document
- Edit analysis data

## TODO

- redo analysis for a receipt (sometimes a lot of categories are set to '-' by the AI)
- connect migros via API wrapper
- connect client to kotlin server
- deploy docker to fly.io.
- serve client assets within server (also in dev env?)
- deal with OpenAI quota errors (back off / retry)
