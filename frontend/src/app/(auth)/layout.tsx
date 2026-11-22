import { brand } from "@/config/brand";

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-100 to-slate-200 p-4">
      <div className="w-full max-w-md">
        <div className="mb-6 flex flex-col items-center text-center">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={brand.logo} alt="" className="mb-2 h-14 w-14" />
          <h1 className="text-2xl font-bold">{brand.name}</h1>
          <p className="text-sm text-slate-500">{brand.tagline}</p>
        </div>
        <div className="card p-6">{children}</div>
      </div>
    </div>
  );
}
