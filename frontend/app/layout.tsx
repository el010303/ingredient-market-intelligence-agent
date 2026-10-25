import type { Metadata } from "next";
import { Bricolage_Grotesque, Figtree } from "next/font/google";
import Nav from "./nav";
import "./globals.css";

// Bricolage Grotesque for headings (warm, a little hand-made, like food packaging),
// Figtree for everything else (friendly and very legible at small sizes).
const display = Bricolage_Grotesque({ subsets: ["latin"], variable: "--font-display", display: "swap" });
const sans = Figtree({ subsets: ["latin"], variable: "--font-sans", display: "swap" });

export const metadata: Metadata = {
  title: "Duty Desk",
  description:
    "Ask what it costs to import a food ingredient. Answers come only from U.S. trade filings and the tariff schedule, with every source linked.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${display.variable} ${sans.variable}`}>
      <body>
        <header className="masthead">
          <div className="masthead-inner">
            <a href="/" className="wordmark">
              <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
                <path d="M12 21c-4.5-2.2-7-5.8-7-10.2C5 6.6 8 3.6 12 3c4 .6 7 3.6 7 7.8 0 4.4-2.5 8-7 10.2Z" fill="currentColor" opacity="0.18" />
                <path d="M12 21V8m0 4.5 3.2-3.2M12 15l-3.2-3.2" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
              </svg>
              Duty Desk
            </a>
            <Nav />
          </div>
        </header>
        <main>{children}</main>
        <footer className="footer">
          <div className="footer-inner">
            <p className="footer-title">Duty Desk</p>
            <p>
              Built from the Federal Register, Regulations.gov (U.S. Trade Representative) and the USITC Harmonized
              Tariff Schedule. For research only; not legal or customs advice.
            </p>
          </div>
        </footer>
      </body>
    </html>
  );
}
