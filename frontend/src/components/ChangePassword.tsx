"use client";
import { useState } from "react";
import { KeyRound } from "lucide-react";
import { api } from "@/lib/api";
import { Modal } from "@/components/ui";
import { Form } from "@/components/Form";
import { useToast } from "@/components/Toast";
import { useSession } from "@/lib/session";

/** Any signed-in user can change their own password (e.g. the temporary one from onboarding). Signs out other devices. */
export function ChangePasswordButton() {
  const [open, setOpen] = useState(false);
  const toast = useToast();
  const { logout } = useSession();
  return (
    <>
      <button className="btn-ghost" title="Change password" onClick={() => setOpen(true)}><KeyRound size={16} /></button>
      <Modal open={open} title="Change password" onClose={() => setOpen(false)}>
        <Form fields={[
          { name: "currentPassword", label: "Current password", type: "password", required: true },
          { name: "newPassword", label: "New password (min 8 characters)", type: "password", required: true },
        ]} submitLabel="Change password" onCancel={() => setOpen(false)}
          onSubmit={async (v) => {
            await api.post("/v1/auth/change-password", v);
            toast.ok("Password changed. Please sign in again.");
            setOpen(false);
            logout();
          }} />
      </Modal>
    </>
  );
}
