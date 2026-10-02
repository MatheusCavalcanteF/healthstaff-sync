import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";

import { Sidebar } from "@/components/layout/Sidebar";

import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: {
    default: "HealthStaff Sync",
    template: "%s | HealthStaff Sync",
  },
  description: "Conciliação de escalas e banco de horas do hospital.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="pt-BR" className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}>
      <body className="flex min-h-full flex-col bg-slate-50 text-slate-900 md:flex-row">
        <Sidebar />
        <main className="flex-1 overflow-y-auto px-4 py-6 md:h-screen md:px-8 md:py-8">{children}</main>
      </body>
    </html>
  );
}
