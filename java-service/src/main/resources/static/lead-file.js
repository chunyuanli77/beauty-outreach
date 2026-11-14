const required = ["username", "source_url"];
const optional = { platform_id: 128, bio: 4000, region: 100, language: 40 };
const fields = new Set([...required, ...Object.keys(optional)]);
const maxRows = 500;

function validateRecord(record, location) {
  const invalid = (message) => { throw new Error(`${location}：${message}`); };
  if (!record || typeof record !== "object" || Array.isArray(record)) {
    invalid("每条线索都应为对象。");
  }
  if (typeof record.username !== "string") invalid("请填写文本格式的 username。");
  const username = record.username.trim();
  if (username.length > 120 || !/^@?[A-Za-z0-9_.]{1,120}$/.test(username.normalize("NFKC").trim())) {
    invalid("username 最多 120 个字符，可用字母、数字、点和下划线，开头可带 @。");
  }
  if (typeof record.source_url !== "string") invalid("请填写文本格式的 source_url。");
  const sourceUrl = record.source_url.trim();
  let url;
  try { url = new URL(sourceUrl); } catch { /* Report a sanitized error below. */ }
  const authority = sourceUrl.match(/^https?:\/\/([^/?#]+)/i)?.[1];
  if (sourceUrl.length > 1024 || !url || !authority || authority.includes("@") ||
      /[\s\x00-\x1f\x7f<>"{}|\\^`]|%(?![0-9a-f]{2})/i.test(sourceUrl)) {
    invalid("source_url 应为不含账户凭据的 HTTP 或 HTTPS 链接。");
  }

  const result = { username, source_url: sourceUrl };
  for (const [key, limit] of Object.entries(optional)) {
    const value = record[key];
    if (value == null) continue;
    if (key === "platform_id" && typeof value === "number") {
      invalid('platform_id 必须用双引号写成 JSON 字符串，例如 "000123"，以保留长编号和前导零。');
    }
    if (typeof value !== "string") invalid(`${key} 应为文本。`);
    const trimmed = value.trim();
    if (trimmed.length > limit) invalid(`${key} 最多 ${limit} 个字符。`);
    if (trimmed) result[key] = trimmed;
  }
  return result;
}

function parseJson(text) {
  let rows;
  try {
    rows = JSON.parse(text);
  } catch {
    throw new Error("JSON 格式无效，请检查引号、逗号和括号。");
  }
  if (!Array.isArray(rows) || rows.length < 1 || rows.length > maxRows) {
    throw new Error("JSON 应为包含 1 至 500 条线索的数组。");
  }
  return rows.map((row, index) => validateRecord(row, `JSON 第 ${index + 1} 条`));
}

function parseCsv(text) {
  const result = [];
  let headers;
  let line = 1;
  let rowStart = 1;
  let cells = [];
  let cell = "";
  let mode = "plain";
  let quotedCell = false;

  const invalid = (message) => { throw new Error(`CSV 第 ${rowStart} 行：${message}`); };
  const finishCell = () => { cells.push(cell); cell = ""; mode = "plain"; };
  const finishRow = () => {
    if (cells.length || cell.trim() || quotedCell) {
      finishCell();
      if (!headers) {
        headers = cells.map((value) => value.trim().toLowerCase());
        if (headers.some((key) => !key)) invalid("表头不能有空列名。");
        if (new Set(headers).size !== headers.length) invalid("表头包含重复列名，请保留唯一列名。");
        if (!required.every((key) => headers.includes(key))) invalid("表头必须包含 username 和 source_url。");
      } else {
        if (cells.length !== headers.length) invalid("字段数量与表头不一致，请检查逗号和引号。");
        if (result.length === maxRows) invalid("一次最多导入 500 条，请拆分文件。");
        const record = {};
        headers.forEach((key, index) => {
          if (fields.has(key)) record[key] = cells[index];
        });
        result.push(validateRecord(record, `CSV 第 ${rowStart} 行`));
      }
    }
    cells = [];
    cell = "";
    mode = "plain";
    quotedCell = false;
  };

  for (let index = 0; index < text.length; index++) {
    const char = text[index];
    if (char === "\r" || char === "\n") {
      let newline = char;
      if (char === "\r" && text[index + 1] === "\n") {
        newline = "\r\n";
        index++;
      }
      if (mode === "quoted") {
        cell += newline;
        line++;
      } else {
        finishRow();
        rowStart = ++line;
      }
    } else if (mode === "quoted") {
      if (char !== '"') {
        cell += char;
      } else if (text[index + 1] === '"') {
        cell += '"';
        index++;
      } else {
        mode = "closed";
      }
    } else if (char === ",") {
      finishCell();
    } else if (mode === "closed") {
      invalid("结束引号后应为逗号或换行。");
    } else if (char === '"') {
      if (cell) invalid("字段中的引号需使用双引号包裹，内部引号写两次。");
      mode = "quoted";
      quotedCell = true;
    } else {
      cell += char;
    }
  }

  if (mode === "quoted") invalid("带引号的字段未闭合。");
  finishRow();
  if (!headers || !result.length) throw new Error("CSV 需要表头和至少一条线索。");
  return result;
}

export function parseLeadFile(fileName, rawText) {
  const text = rawText.replace(/^\uFEFF/, "");
  if (/\.csv$/i.test(fileName)) return parseCsv(text);
  if (/\.json$/i.test(fileName)) return parseJson(text);
  throw new Error("请选择扩展名为 .csv 或 .json 的文件。");
}
