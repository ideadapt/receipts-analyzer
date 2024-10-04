import { Status } from "https://deno.land/std@0.153.0/http/http_status.ts";
import {
  Application,
  NativeRequest,
  Request,
  Response,
  Router,
} from "https://deno.land/x/oak@v11.1.0/mod.ts";
import { config } from "https://deno.land/x/dotenv@v3.2.0/mod.ts";
import OpenAI from "https://deno.land/x/openai@v4.58.2/mod.ts";
import {
  FileCitationAnnotation,
} from "https://deno.land/x/openai@v4.58.2/resources/beta/threads/messages.ts";
import { Uploadable } from "https://deno.land/x/openai@v4.58.2/core.ts";

function getConfig(key: string): string {
  let envVal = Deno.env.get(key);
  if (!envVal) {
    envVal = config({ path: `${Deno.cwd()}/.env` })[key];
  }
  console.log(`env: ${key}=${envVal}`);
  return envVal;
}

const allowedOriginHosts = getConfig("allowed_origin_hosts").split(",");
const gh_gist_token = getConfig("gh_gist_token");
const editor_password = getConfig("editor_password");
const openAiApiKey = getConfig("open_ai_api_key");

const app = new Application();
const router = new Router();
const ghApiOpts = {
  headers: new Headers({
    "Authorization": 'Bearer ' + gh_gist_token,
    "X-GitHub-Api-Version": "2022-11-28",
    "accept": "application/json",
  }),
};
const octokit = (path: string, method = "GET", body: object | null = null) =>
  fetch("https://api.github.com" + path, {
    method,
    ...ghApiOpts,
    body: body ? JSON.stringify(body) : null,
  });
const rawOctokit = (path: string) =>
  fetch("https://gist.githubusercontent.com" + path, ghApiOpts);

const client = new OpenAI({ apiKey: openAiApiKey });

function isAuthorized(request: Request) {
  const auth = request.headers.get("Authorization");
  if (auth) {
    const [_, token] = auth.split("Bearer ");
    const normalizedToken = (token || "").trim();
    console.log(
      "Authorization token",
      normalizedToken.substring(0, Math.min(normalizedToken.length, 5)),
    );
    return normalizedToken === editor_password;
  }
}

function applyCorsHeaders(response: Response, request: Request) {
  const origin = request.headers.get("origin") as string;
  if (origin) {
    const originUrl = new URL(origin);
    if (allowedOriginHosts.includes(originUrl.hostname)) {
      const origin = originUrl.origin;
      response.headers.set("Access-Control-Allow-Origin", origin);
    }
    response.headers.set(
      "Access-Control-Allow-Methods",
      "OPTIONS, PATCH, POST, GET",
    );
    response.headers.set(
      "Access-Control-Allow-Headers",
      "Content-Type, Authorization",
    );
  }
  response.status = Status.OK;
}

router.get("/gists/:gist_id", async (context) => {
  console.log("GET gist");

  const gist = await (await octokit(`/gists/${context.params.gist_id}`)).json();
  const gistContent: { [fileName: string]: object } = {};
  for (const [fileName, file] of Object.entries(gist.files) as any) {
    // if db.csv content is >1MB, github will truncate
    // and only serve full content via raw_url.
    let fileContent = "";
    if (file.truncated === true) {
      const rawUrl = new URL(file.raw_url);
      const rawFileContent = await (await rawOctokit(`${rawUrl.pathname}`))
        .text();
      fileContent = rawFileContent;
    } else {
      fileContent = file.content;
    }

    gistContent[fileName] = { content: fileName.endsWith('.json') ? JSON.parse(fileContent) : fileContent };
  }

  applyCorsHeaders(context.response, context.request);
  context.response.headers.set("Content-Type", "application/json");
  context.response.body = JSON.parse(JSON.stringify(gistContent));
  context.response.status = Status.OK;
});

router
  .options(
    "/gists/:gist_id",
    ({ request, response }) => applyCorsHeaders(response, request),
  )
  .patch("/gists/:gist_id", async ({ request, response, params }) => {
    console.log("PATCH gist");
    applyCorsHeaders(response, request);

    if (!isAuthorized(request)) {
      console.log("Not authorized!");
      response.status = Status.Unauthorized;
      return;
    }

    const state = await request.body({ type: "json" }).value;
    for (const [fileName, fileObj] of Object.entries(state) as any) {
      fileObj.content = fileName.endsWith('.json') ? JSON.stringify(fileObj.content) : fileObj.content + "";
    }
    const res = await octokit(`/gists/${params.gist_id}`, "PATCH", {
      description: "receipts-db",
      files: state, // { [filename: string]: { content: string }}
    });
    const status = res.ok ? Status.OK : res.status;
    if(!res.ok) console.error(await res.json());

    response.headers.set("Content-Type", "application/json");
    response.status = status;
    response.body = JSON.stringify({});
  });

router.post("/receipts", async ({ request, response }) => {
  console.log("POST receipts");

  const assistant_name = "asst_pdf_receipts_reader_v7";
  const existingAssistant =
    (await client.beta.assistants.list()).data.filter((a) =>
      a.name === assistant_name
    )[0];
  let assistant = null;
  if (!existingAssistant) {
    console.log("creating assistant " + assistant_name);

    assistant = await client.beta.assistants.create({
      name: assistant_name,
      instructions:
        "You can read tabular data from a shopping receipt and output this data in propper CSV format." +
        "You never include anything but the raw CSV rows. You omit the surrounding markdown code blocks." +
        "Make sure you never remove the header row containing the column titles." +
        "You always add an extra column at the end called 'Category', which categorizes the shopping item based on its name." +
        "The receipts are in german, so you have to use german category names. Try to use one of the following category names: " +
        "Frucht, Gemüse, Milchprodukt, Käse, Eier, Öl, Süssigkeit, Getränk, Alkohol, Fleisch, Fleischersatz, Gebäck." +
        "You may add another category if none of the examples match." +
        "Add another extra column at the end called 'Datetime' that contains the date and time of the receipt. The receipt date and time value is the same for every shopping item." +
        "Add another extra column at the end called 'Seller' that contains the name of the receipt issuer (e.g. store name). The seller value is the same for every shopping item." +
        "If the seller name contains one of: 'Migros', 'Coop', 'Aldi', 'Lidl', use that short form."
        ,
      model: "gpt-4o-mini",
      tools: [{ type: "file_search" }],
    });
  } else {
    assistant = existingAssistant;
  }

  const rawRequest = (request.originalRequest as NativeRequest).request;
  const openAiFile = await client.files.create({
    file: (await rawRequest.formData()).get("files") as unknown as Uploadable,
    purpose: "assistants",
  });

  const thread = await client.beta.threads.create({
    messages: [
      {
        role: "user",
        content:
          "Extract tabular data from attached receipt. The columns in the receipt are Artikelbezeichnung, Menge, Preis, Total.",
        attachments: [{
          file_id: openAiFile.id,
          tools: [{ type: "file_search" }],
        }],
      },
    ],
  });

  const run = await client.beta.threads.runs.createAndPoll(thread.id, {
    assistant_id: assistant.id,
  });

  const messages = await client.beta.threads.messages.list(thread.id, {
    run_id: run.id,
  });

  const message = messages.data.pop()!;
  if (message.content[0].type === "text") {
    const { text } = message.content[0];
    const { annotations } = text;
    const citations: string[] = [];

    let index = 0;
    for (const annotation of annotations) {
      text.value = text.value.replace(annotation.text, "[" + index + "]");
      const { file_citation } = annotation as FileCitationAnnotation;
      if (file_citation) {
        const citedFile = await client.files.retrieve(file_citation.file_id);
        citations.push("[" + index + "]" + citedFile.filename);
      }
      index++;
    }

    applyCorsHeaders(response, request);
    response.headers.set("Content-Type", "application/json");
    response.status = Status.OK;
    response.body = JSON.stringify({
      csv: text.value
    });
  }
});

app.use(router.routes());
app.use(async (context, next) => {
  try {
    await context.send({
      root: `${Deno.cwd()}/dist`,
      index: "index.html",
    });
  } catch (_) {
    await next();
  }
});
app.use(router.allowedMethods());

export default app;
