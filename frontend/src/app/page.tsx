"use client";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useSession } from "@/lib/session";
import { Loading } from "@/components/ui";
import { homeFor } from "@/components/nav";

export default function Home() {
  const { me, loading } = useSession();
  const router = useRouter();
  useEffect(() => {
    if (loading) return;
    router.replace(me ? homeFor(me) : "/login");
  }, [me, loading, router]);
  return <Loading />;
}
