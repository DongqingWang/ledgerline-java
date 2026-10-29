const host = location.hostname;
const endpoint = port => `http://${host}:${port}`;
const $ = id => document.getElementById(id);
$('dedup').value = crypto.randomUUID();
async function json(url, body) {
  const res = await fetch(url, body ? {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)} : {});
  const text = await res.text();
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${text}`);
  return JSON.parse(text);
}
async function refresh() {
  try {
    const balances = await json(`${endpoint(8082)}/v1/balances`);
    const fragment = document.createDocumentFragment();
    for (const balance of balances) {
      const tr = document.createElement('tr');
      for (const field of ['account_id', 'currency', 'balance_minor', 'last_raft_index']) {
        const td = document.createElement('td'); td.textContent = balance[field]; tr.append(td);
      }
      fragment.append(tr);
    }
    $('balances').replaceChildren(fragment);
    $('index').textContent = `${balances.length} cached accounts`;
    $('read-error').textContent = balances.length ? '' : 'No accounts yet. Run scripts/local-demo.sh to seed the ledger.';
  } catch (error) { $('read-error').textContent = `Balance reader unavailable: ${error.message}`; }
}
$('refresh').addEventListener('click', refresh);
for (const id of ['transfer', 'fx']) {
  $(id).addEventListener('submit', async event => {
    event.preventDefault();
    const button = event.target.querySelector('button'); button.disabled = true;
    const body = Object.fromEntries(new FormData(event.target));
    for (const field of ['amount_minor', 'fee_minor']) {
      if (field in body) {
        body[field] = Number(body[field]);
        if (!Number.isSafeInteger(body[field])) {
          $(`${id}-result`).textContent = 'Amount must be an integer within JavaScript’s safe integer range.';
          button.disabled = false; return;
        }
      }
    }
    try {
      const result = await json(id === 'transfer' ? `${endpoint(8080)}/v1/transfers` : `${endpoint(8083)}/v1/convert`, body);
      $(`${id}-result`).textContent = JSON.stringify(result, null, 2);
      if (id === 'transfer') { $('dedup').value = crypto.randomUUID(); setTimeout(refresh, 300); }
    } catch (error) { $(`${id}-result`).textContent = error.message; }
    finally { button.disabled = false; }
  });
}
refresh();
