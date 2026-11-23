"use client";
import { api } from "./api";

export type PhotoKind = "SELFIE" | "TEAM" | "REGISTER" | "PROFILE";

/** Draws a video frame / image onto a canvas no larger than maxSide and returns JPEG base64 (no data: prefix). */
function toJpeg(src: CanvasImageSource, w: number, h: number, maxSide = 640, quality = 0.7): string {
  const scale = Math.min(1, maxSide / Math.max(w, h));
  const c = document.createElement("canvas");
  c.width = Math.round(w * scale);
  c.height = Math.round(h * scale);
  c.getContext("2d")!.drawImage(src, 0, 0, c.width, c.height);
  return c.toDataURL("image/jpeg", quality).split(",")[1];
}

export function captureVideo(v: HTMLVideoElement, maxSide = 640): string {
  return toJpeg(v, v.videoWidth, v.videoHeight, maxSide);
}

/** Downscales a picked/captured file. Registers keep more resolution so handwriting stays readable. */
export function fileToJpeg(file: File, maxSide = 640): Promise<string> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => { try { resolve(toJpeg(img, img.naturalWidth, img.naturalHeight, maxSide, 0.75)); } catch (e) { reject(e); } finally { URL.revokeObjectURL(url); } };
    img.onerror = () => { URL.revokeObjectURL(url); reject({ message: "Could not read the photo" }); };
    img.src = url;
  });
}

export async function uploadPhoto(kind: PhotoKind, dataBase64: string, extra: { siteId?: string; employeeId?: string; takenAt?: string } = {}) {
  const r = await api.post<{ id: string }>("/v1/attendance/photos", { kind, contentType: "image/jpeg", dataBase64, ...extra });
  return r.id;
}
