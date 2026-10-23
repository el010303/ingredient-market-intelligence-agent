import type { Metadata } from "next";
import { IBM_Plex_Mono, Public_Sans, Source_Serif_4 } from "next/font/google";
import Nav from "./nav";
import "./globals.css";

// Public Sans is the typeface of the U.S. Web Design System, used across federal sites.
const sans = Public_Sans({ subsets: ["latin"], variable: "--font-sans", display: "swap" });
const serif = Source_Serif_4({ subsets: ["latin"], variable: "--font-serif", display: "swap" });
const mono = IBM_Plex_Mono({ subsets: ["latin"], weight: ["400", "500"], variable: "--font-mono", display: "swap" });

export const metadata: Metadata = {
  title: "Duty Desk",
  description:
    "Ask what it costs to import an ingredient. Answers come only from U.S. trade filings and the tariff schedule, with every step shown.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${sans.variable} ${serif.variable} ${mono.variable}`}>
      <body>
        <header className="masthead">
          <div className="masthead-inner">
            <a href="/" className="wordmark">
              Duty Desk
            </a>
            <Nav />
          </div>
        </header>
        <main className="page">{children}</main>
        <footer className="footer">
          Sources: Federal Register, Regulations.gov (USTR), USITC Harmonized Tariff Schedule. Not legal or customs advice.
        </footer>
      </body>
    </html>
  );
}
