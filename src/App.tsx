import { Activity, ArrowDownUp, CandlestickChart, CircleAlert, LockKeyhole, RefreshCw, Settings2, ShieldCheck, WifiOff } from "lucide-react";
import { useState } from "react";

type Tab = "overview" | "scanner" | "security";

function App() {
  const [tab, setTab] = useState<Tab>("overview");

  return (
    <main className="app-shell">
      <header className="topbar">
        <a className="brand" href="/" aria-label="ArbixG home">
          <span className="brand-mark">G</span>
          <span>ARBIX<span className="gold">G</span></span>
        </a>
        <div className="connection-pill"><span className="status-dot" /> LIVE DATA NOT CONNECTED</div>
      </header>

      <section className="welcome">
        <div>
          <p className="eyebrow">PRODUCTION WORKSPACE</p>
          <h1>Market intelligence<span className="gold">.</span></h1>
          <p className="subheading">Triangular arbitrage, with transparent live exchange data.</p>
        </div>
        <button className="icon-button" aria-label="Refresh status" onClick={() => window.location.reload()}><RefreshCw size={18} /></button>
      </section>

      <nav className="tabs" aria-label="Main navigation">
        <button className={tab === "overview" ? "tab active" : "tab"} onClick={() => setTab("overview")}><Activity size={16}/> Overview</button>
        <button className={tab === "scanner" ? "tab active" : "tab"} onClick={() => setTab("scanner")}><CandlestickChart size={16}/> Scanner</button>
        <button className={tab === "security" ? "tab active" : "tab"} onClick={() => setTab("security")}><ShieldCheck size={16}/> Security</button>
      </nav>

      {tab === "overview" && (
        <section className="content-grid">
          <article className="panel primary-panel">
            <div className="panel-heading"><span>EXCHANGE CONNECTION</span><WifiOff size={17}/></div>
            <div className="empty-state">
              <span className="empty-icon"><ArrowDownUp size={25}/></span>
              <h2>No exchange connected</h2>
              <p>Connect a supported exchange to load genuine market prices, order books, balances and trading fees. No sample or simulated figures are shown.</p>
              <button className="primary-button" onClick={() => setTab("security")}>Review secure connection</button>
            </div>
          </article>
          <article className="panel status-panel">
            <div className="panel-heading"><span>DATA INTEGRITY</span><ShieldCheck size={17}/></div>
            <div className="integrity-row"><span className="status-indicator warning"/><div><strong>Awaiting live source</strong><small>Market feed not configured</small></div></div>
            <div className="integrity-row"><span className="status-indicator muted"/><div><strong>Scanner inactive</strong><small>Requires verified market data</small></div></div>
            <div className="integrity-row"><span className="status-indicator muted"/><div><strong>Trading disabled</strong><small>Risk controls not configured</small></div></div>
          </article>
        </section>
      )}

      {tab === "scanner" && (
        <section className="panel scanner-panel">
          <div className="panel-heading"><span>TRIANGULAR ARBITRAGE SCANNER</span><CandlestickChart size={17}/></div>
          <div className="empty-state">
            <span className="empty-icon"><CircleAlert size={25}/></span>
            <h2>Scanner awaiting live market feeds</h2>
            <p>Opportunities will appear only after exchange market metadata, executable order-book depth, trading fees and quote timestamps are available.</p>
            <span className="state-label">NOT RUNNING — NO LIVE DATA SOURCE</span>
          </div>
        </section>
      )}

      {tab === "security" && (
        <section className="panel security-panel">
          <div className="panel-heading"><span>SECURITY & CONNECTIONS</span><LockKeyhole size={17}/></div>
          <div className="security-notice"><ShieldCheck size={20}/><p>Exchange API secrets must be stored server-side in encrypted secrets storage. Never paste secret keys into this browser app or commit them to GitHub.</p></div>
          <div className="setting-row"><div><strong>Exchange API connection</strong><small>No exchange credentials configured</small></div><span className="state-label">NOT CONNECTED</span></div>
          <div className="setting-row"><div><strong>Withdrawals permission</strong><small>Must remain disabled on exchange API keys</small></div><span className="state-label safe">DISABLED</span></div>
          <div className="setting-row"><div><strong>Automatic trading</strong><small>Locked until live execution and risk checks pass</small></div><span className="state-label">LOCKED</span></div>
        </section>
      )}

      <footer className="footer"><span>ARBIXG · PRODUCTION FOUNDATION</span><span><Settings2 size={13}/> Secure by design</span></footer>
    </main>
  );
}

export default App;
