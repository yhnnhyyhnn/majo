import { useState, useEffect } from "react";

const API = "/api";

export default function useSettings() {
  const [config, setConfig] = useState({
    baseUrl: "https://api.openai.com/v1",
    apiKey: "",
    modelName: "gpt-4o-mini",
    workspace: "",
  });
  const [loading, setLoading] = useState(true);

  const loadSettings = async () => {
    setLoading(true);
    try {
      const res = await fetch(API + "/settings");
      const data = await res.json();
      setConfig((prev) => ({
        ...prev,
        baseUrl: data.baseUrl || prev.baseUrl,
        apiKey: data.apiKey || prev.apiKey,
        modelName: data.modelName || prev.modelName,
        workspace: data.workspace || prev.workspace,
      }));
    } catch {
      // keep defaults
    } finally {
      setLoading(false);
    }
  };

  const saveSettings = async (partial) => {
    try {
      await fetch(API + "/settings", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(partial),
      });
    } catch {
      // silently fail
    }
  };

  useEffect(() => {
    loadSettings();
  }, []);

  return { config, setConfig, loading, loadSettings, saveSettings };
}
