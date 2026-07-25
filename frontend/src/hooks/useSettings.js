import { useState, useEffect, useCallback } from "react";

const API = "/api";

export default function useSettings() {
  const [config, setConfig] = useState({
    baseUrl: "https://api.openai.com/v1",
    apiKey: "",
    modelName: "gpt-4o-mini",
    workspace: "",
  });
  const [loading, setLoading] = useState(true);
  const [modelConfigs, setModelConfigs] = useState([]);
  const [activeModelId, setActiveModelId] = useState(null);

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

  const loadModelConfigs = useCallback(async () => {
    try {
      const res = await fetch(API + "/models");
      const data = await res.json();
      setModelConfigs(data);
    } catch {
      // ignore
    }
  }, []);

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

  const addModelConfig = useCallback(async (model) => {
    try {
      const res = await fetch(API + "/models", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(model),
      });
      const created = await res.json();
      await loadModelConfigs();
      return created;
    } catch {
      return null;
    }
  }, [loadModelConfigs]);

  const updateModelConfig = useCallback(async (id, partial) => {
    try {
      await fetch(API + "/models/" + id, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(partial),
      });
      await loadModelConfigs();
    } catch {
      // ignore
    }
  }, [loadModelConfigs]);

  const deleteModelConfig = useCallback(async (id) => {
    try {
      await fetch(API + "/models/" + id, { method: "DELETE" });
      if (activeModelId === id) setActiveModelId(null);
      await loadModelConfigs();
    } catch {
      // ignore
    }
  }, [activeModelId, loadModelConfigs]);

  useEffect(() => {
    loadSettings();
    loadModelConfigs();
  }, []);

  return {
    config,
    setConfig,
    loading,
    loadSettings,
    saveSettings,
    modelConfigs,
    loadModelConfigs,
    addModelConfig,
    updateModelConfig,
    deleteModelConfig,
    activeModelId,
    setActiveModelId,
  };
}
