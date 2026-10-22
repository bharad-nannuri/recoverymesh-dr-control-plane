const $ = (id) => document.getElementById(id);

async function api(path, options = {}) {
  const res = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...options });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
  return data;
}

function setDbCard(card, healthy, active) {
  card.classList.toggle('down', !healthy);
  card.classList.toggle('active', healthy && active);
  card.classList.toggle('standby', healthy && !active);
}

function paintStatus(s) {
  $('systemMode').textContent = s.mode.replaceAll('_', ' ');
  $('activeSite').textContent = s.activeSite;
  $('activeSiteNote').textContent = s.mode === 'DR_ACTIVE' ? 'Disaster recovery site serving traffic' : s.mode.replaceAll('_', ' ').toLowerCase();
  $('rto').textContent = s.lastRtoMs ? `${(s.lastRtoMs / 1000).toFixed(2)}s` : '—';
  $('lag').textContent = s.replicationLag < 0 ? '—' : s.replicationLag;
  $('queued').textContent = s.queuedWrites;
  $('accepted').textContent = s.totalAccepted;
  $('failovers').textContent = s.failoverCount;
  $('primaryCount').textContent = s.primaryRecords < 0 ? '—' : s.primaryRecords;
  $('secondaryCount').textContent = s.secondaryRecords < 0 ? '—' : s.secondaryRecords;
  $('primaryHealth').textContent = s.primaryHealthy ? 'HEALTHY' : 'UNREACHABLE';
  $('secondaryHealth').textContent = s.secondaryHealthy ? 'HEALTHY' : 'UNREACHABLE';
  $('primaryRole').textContent = s.activeSite === 'PRIMARY' ? 'ACTIVE' : 'STANDBY';
  $('secondaryRole').textContent = s.activeSite === 'SECONDARY' ? 'ACTIVE / DR' : 'STANDBY';
  $('routerLabel').textContent = s.mode === 'FAILING_OVER' ? 'Failover in progress — writes are queued' : `Routing writes to ${s.activeSite}`;
  $('replicationLabel').textContent = s.inSync ? 'in sync' : 'reconciliation needed';
  $('lastAction').textContent = s.lastAction;
  $('syncBadge').textContent = s.inSync ? 'IN SYNC' : 'OUT OF SYNC';
  $('syncBadge').classList.toggle('warn', !s.inSync);
  setDbCard($('primaryCard'), s.primaryHealthy, s.activeSite === 'PRIMARY');
  setDbCard($('secondaryCard'), s.secondaryHealthy, s.activeSite === 'SECONDARY');
  $('primaryHealth').parentElement.style.color = s.primaryHealthy ? 'var(--green)' : 'var(--red)';
  $('secondaryHealth').parentElement.style.color = s.secondaryHealthy ? 'var(--green)' : 'var(--red)';
}

function paintTransactions(rows) {
  const body = $('transactionsBody');
  if (!rows.length) {
    body.innerHTML = '<tr><td colspan="5" class="empty">No transactions yet.</td></tr>';
    return;
  }
  body.innerHTML = rows.slice(0, 15).map(t => `
    <tr>
      <td><strong>${escapeHtml(t.shipmentId)}</strong><br><small>${escapeHtml(t.customer)}</small></td>
      <td>${escapeHtml(t.origin)} → ${escapeHtml(t.destination)}</td>
      <td><span class="site-tag">${t.acceptedBy}</span></td>
      <td class="presence ${t.onPrimary ? 'yes' : 'no'}">${t.onPrimary ? '✓' : '—'}</td>
      <td class="presence ${t.onSecondary ? 'yes' : 'no'}">${t.onSecondary ? '✓' : '—'}</td>
    </tr>`).join('');
}

function paintEvents(events) {
  const el = $('timeline');
  if (!events.length) { el.innerHTML = '<div class="empty">Waiting for events…</div>'; return; }
  el.innerHTML = events.slice(0, 20).map(e => `
    <div class="timeline-item ${e.severity}">
      <strong>${escapeHtml(e.message)}</strong>
      <small>${new Date(e.timestamp).toLocaleTimeString()} · ${e.severity}</small>
    </div>`).join('');
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>'"]/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[ch]));
}

async function refresh() {
  try {
    const [status, txs, events] = await Promise.all([
      api('/api/status'), api('/api/transactions'), api('/api/events')
    ]);
    paintStatus(status); paintTransactions(txs); paintEvents(events);
  } catch (e) {
    $('lastAction').textContent = `Runtime unavailable: ${e.message}`;
  }
}

async function action(path, button) {
  button.disabled = true;
  try { await api(path, { method: 'POST' }); await refresh(); }
  catch (e) { $('submitResult').textContent = e.message; }
  finally { button.disabled = false; }
}

$('shipmentForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const btn = e.currentTarget.querySelector('button[type="submit"]');
  btn.disabled = true;
  try {
    const tx = await api('/api/transactions', {
      method: 'POST',
      body: JSON.stringify({
        shipmentId: $('shipmentId').value,
        customer: $('customer').value,
        origin: $('origin').value,
        destination: $('destination').value,
        amount: Number($('amount').value || 0)
      })
    });
    $('submitResult').textContent = `${tx.shipmentId} accepted by ${tx.acceptedBy}.`;
    $('shipmentId').value = '';
    await refresh();
  } catch (err) { $('submitResult').textContent = err.message; }
  finally { btn.disabled = false; }
});

$('generate50Btn').addEventListener('click', async () => {
  $('generate50Btn').disabled = true;
  $('submitResult').textContent = 'Generating 50 real transactions…';
  try {
    const result = await api('/api/traffic/generate?count=50', { method:'POST' });
    $('submitResult').textContent = `${result.generated} transactions generated.`;
    await refresh();
  } catch(e) { $('submitResult').textContent = e.message; }
  finally { $('generate50Btn').disabled = false; }
});

$('failPrimaryBtn').addEventListener('click', () => action('/api/control/fail-primary', $('failPrimaryBtn')));
$('restorePrimaryBtn').addEventListener('click', () => action('/api/control/restore-primary', $('restorePrimaryBtn')));
$('reconcileBtn').addEventListener('click', () => action('/api/control/reconcile', $('reconcileBtn')));
$('failbackBtn').addEventListener('click', () => action('/api/control/failback', $('failbackBtn')));
$('resetBtn').addEventListener('click', () => action('/api/control/reset', $('resetBtn')));

refresh();
setInterval(refresh, 1500);
