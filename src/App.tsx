import { useState } from "react";
import {
  Activity, ArrowDownUp, Bell, CandlestickChart, ChevronRight, CircleHelp,
  CircleUserRound, Gauge, Gem, LayoutDashboard, LockKeyhole, Menu,
  RefreshCw, ScanLine, Settings, ShieldCheck, Wallet, Wifi, WifiOff, X
} from "lucide-react";

type Tab = "home" | "markets" | "scanner" | "portfolio" | "more";

function App() {
  const [tab, setTab] = useState<Tab>("home");
  const [menuOpen, setMenuOpen] = useState(false);
  const [hideBalance, setHideBalance] = useState(false);

  const nav = [
    { id: "home" as Tab, label: "Home", icon: LayoutDashboard },
    { id: "markets" as Tab, label: "Markets", icon: CandlestickChart },
    { id: "scanner" as Tab, label: "Scanner", icon: ScanLine },
    { id: "portfolio" as Tab, label: "Portfolio", icon: Wallet },
    { id: "more" as Tab, label: "More", icon: Menu },
  ];

  const title = {
    home: "Your trading command centre",
    markets: "Market watch",
    scanner: "Arbitrage scanner",
    portfolio: "Your portfolio",
    more: "Account & settings",
  }[tab];

  return (
    <main className="app-shell">
      <header className="topbar">
        <button className="mobile-menu icon-button" aria-label="Open menu" onClick={() => setMenuOpen(!menuOpen)}>
          {menuOpen ? <X size={19}/> : <Menu size={19}/>}
        </button>
        <a className="brand" href="/" aria-label="ArbixG home">
          <span className="brand-emblem">G</span>
          <span className="brand-word">arbix<span>G</span></span>
        </a>
        <div className="topbar-right">
          <span className="connection-pill"><span className="status-dot"/> DATA SOURCE NOT CONNECTED</span>
          <button className="icon-button notification-button" aria-label="Notifications"><Bell size={18}/><i/></button>
          <button className="profile-button" aria-label="Profile"><CircleUserRound size={21}/></button>
        </div>
      </header>

      {menuOpen && <div className="mobile-menu-panel">{nav.map(({id,label,icon:Icon}) => <button key={id} onClick={() => {setTab(id);setMenuOpen(false)}}><Icon size={17}/>{label}</button>)}</div>}

      <section className="welcome-row">
        <div>
          <p className="eyebrow">ELITE GOLD · TRADING WORKSPACE</p>
          <h1>{title}<span className="gold">.</span></h1>
          <p className="subheading">One view for smarter, data-driven arbitrage.</p>
        </div>
        <button className="icon-button refresh-button" aria-label="Refresh"><RefreshCw size={17}/></button>
      </section>

      <div className="content">
        {tab === "home" && <>
          <section className="hero-card">
            <div className="hero-art" aria-hidden="true"><div className="orbit orbit-one"/><div className="orbit orbit-two"/><div className="globe"><span>G</span></div><div className="hero-bars"><i/><i/><i/><i/><i/><i/><i/></div></div>
            <div className="hero-copy">
              <p className="eyebrow">ACCOUNT OVERVIEW</p>
              <div className="balance-label">Total balance <button className="eye-button" onClick={() => setHideBalance(!hideBalance)}>{hideBalance ? "SHOW" : "HIDE"}</button></div>
              <div className="balance-value">{hideBalance ? "••••••••" : "— — —"}</div>
              <p className="balance-note">Connect an exchange to securely load your balances.</p>
              <div className="hero-divider"/>
              <div className="profit-line"><span>Today's realised profit</span><strong>—</strong></div>
              <span className="data-caption">LIVE ACCOUNT DATA REQUIRED</span>
            </div>
            <div className="hero-glow"/>
          </section>

          <section className="quick-actions">
            <button className="action-card featured" onClick={() => setTab("scanner")}><span className="action-icon"><ScanLine size={21}/></span><strong>Scan now</strong><small>Find opportunities</small><ChevronRight size={16} className="action-arrow"/></button>
            <button className="action-card" onClick={() => setTab("markets")}><span className="action-icon"><CandlestickChart size={21}/></span><strong>Markets</strong><small>Explore prices</small><ChevronRight size={16} className="action-arrow"/></button>
            <button className="action-card" onClick={() => setTab("portfolio")}><span className="action-icon"><Wallet size={21}/></span><strong>Portfolio</strong><small>View holdings</small><ChevronRight size={16} className="action-arrow"/></button>
            <button className="action-card" onClick={() => setTab("more")}><span className="action-icon"><Settings size={21}/></span><strong>Settings</strong><small>Manage account</small><ChevronRight size={16} className="action-arrow"/></button>
          </section>

          <section className="scanner-banner">
            <div className="radar-wrap"><div className="radar-ring r1"/><div className="radar-ring r2"/><div className="radar-ring r3"/><div className="radar-sweep"/><ScanLine size={25}/></div>
            <div className="scanner-banner-copy"><div className="section-kicker"><span className="status-dot offline"/> LIVE ARBITRAGE SCANNER</div><h2>Ready when your market feeds are connected</h2><p>Scanning is paused to prevent false or simulated opportunities.</p><span className="scanner-state">● WAITING FOR LIVE DATA</span></div>
            <button className="outline-button" onClick={() => setTab("scanner")}>Open scanner <ChevronRight size={16}/></button>
          </section>

          <div className="lower-grid">
            <section className="panel opportunities-panel">
              <div className="panel-heading"><div><span className="heading-icon"><Gem size={17}/></span><h2>Top opportunities</h2></div><button className="text-link" onClick={() => setTab("scanner")}>View all <ChevronRight size={14}/></button></div>
              <div className="empty-table"><div className="empty-table-icon"><ArrowDownUp size={20}/></div><strong>No verified opportunities yet</strong><p>Connect live exchange feeds to calculate spreads after fees and slippage.</p></div>
            </section>
            <section className="panel market-panel">
              <div className="panel-heading"><div><span className="heading-icon"><Activity size={17}/></span><h2>Market overview</h2></div><button className="text-link" onClick={() => setTab("markets")}>Markets <ChevronRight size={14}/></button></div>
              <div className="market-empty"><CandlestickChart size={26}/><strong>Market feed unavailable</strong><span>Live prices will appear here</span></div>
            </section>
          </div>

          <section className="trust-strip"><div><ShieldCheck size={18}/><span><strong>Security first</strong><small>API secrets stay off the client</small></span></div><div><Gauge size={18}/><span><strong>Real calculations</strong><small>Fees and slippage included</small></span></div><div><WifiOff size={18}/><span><strong>Honest data status</strong><small>No fabricated market figures</small></span></div></section>
        </>}

        {tab === "scanner" && <section className="panel page-panel">
          <div className="panel-heading"><div><span className="heading-icon"><ScanLine size={18}/></span><h2>Triangular arbitrage scanner</h2></div><span className="state-tag">INACTIVE</span></div>
          <div className="scanner-page-visual"><div className="large-radar"><div/><div/><div/><ScanLine size={32}/></div><h2>Waiting for live exchange feeds</h2><p>The scanner will evaluate all eligible trading routes across connected exchanges. It will account for order-book depth, trading fees, slippage, minimum order sizes and data freshness before showing an opportunity.</p><div className="scanner-checks"><span><i/> Exchange connection required</span><span><i/> Live order books required</span><span><i/> Risk checks required</span></div></div>
        </section>}

        {tab === "markets" && <section className="panel page-panel"><div className="panel-heading"><div><span className="heading-icon"><CandlestickChart size={18}/></span><h2>Live markets</h2></div><span className="state-tag">NO FEED</span></div><div className="simple-empty"><Activity size={28}/><h2>Connect an exchange to view markets</h2><p>Prices, 24-hour changes and pair data will be loaded from verified exchange APIs.</p></div></section>}

        {tab === "portfolio" && <section className="panel page-panel"><div className="panel-heading"><div><span className="heading-icon"><Wallet size={18}/></span><h2>Portfolio & holdings</h2></div><span className="state-tag">NOT CONNECTED</span></div><div className="simple-empty"><Wallet size={28}/><h2>Your portfolio will appear here</h2><p>Connect an exchange with read-only permissions first. Trading and withdrawals remain disabled until separately configured.</p></div></section>}

        {tab === "more" && <section className="panel page-panel"><div className="panel-heading"><div><span className="heading-icon"><Settings size={18}/></span><h2>Account, security & settings</h2></div></div><div className="settings-list"><div className="setting-row"><span className="setting-icon"><LockKeyhole size={18}/></span><div><strong>API key security</strong><small>Store secrets on a secure backend only</small></div><span className="state-tag">REQUIRED</span></div><div className="setting-row"><span className="setting-icon"><ShieldCheck size={18}/></span><div><strong>Withdrawal permission</strong><small>Keep withdrawal access disabled</small></div><span className="safe-tag">DISABLED</span></div><div className="setting-row"><span className="setting-icon"><CircleHelp size={18}/></span><div><strong>Help & support</strong><small>Guides and account assistance</small></div><ChevronRight size={17}/></div></div></section>}
      </div>

      <nav className="bottom-nav" aria-label="Primary navigation">
        {nav.map(({id,label,icon:Icon}) => <button key={id} className={tab === id ? "bottom-item active" : "bottom-item"} onClick={() => {setTab(id);setMenuOpen(false)}}><Icon size={19}/><span>{label}</span></button>)}
      </nav>
      <footer className="footer"><span>ARBIXG <b>·</b> ONE ECOSYSTEM. ENDLESS POSSIBILITIES.</span><span><span className="footer-dot"/> Secure workspace</span></footer>
    </main>
  );
}

export default App;
