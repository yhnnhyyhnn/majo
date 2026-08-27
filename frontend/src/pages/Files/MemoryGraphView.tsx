import { useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { agentsApi, type MemoryGraphNode, type MemoryGraphSnapshot } from "../../api/modules/agents";
import styles from "./MemoryGraphView.module.less";

interface GraphNode {
  id: string;
  x: number;
  y: number;
  vx: number;
  vy: number;
  data: MemoryGraphNode;
}

interface GraphLink {
  source: string;
  target: string;
}

interface MemoryGraphViewProps {
  agentId: string;
  onOpenFile: (source: "daily" | "digest", path: string) => void;
}

const WIDTH = 640;
const HEIGHT = 420;

/** Simple deterministic circle layout refined by a few spring iterations. */
function buildLayout(snapshot: MemoryGraphSnapshot): {
  nodes: Map<string, GraphNode>;
  links: GraphLink[];
} {
  const nodeIds = snapshot.nodes.map((n) => n.id);
  const count = Math.max(nodeIds.length, 1);
  const nodes = new Map<string, GraphNode>();

  // Deterministic placement: digest roots near the centre ring, files outside.
  let fileIndex = 0;
  let rootIndex = 0;
  const rootCount = Math.max(
    snapshot.nodes.filter((n) => n.id.startsWith("root:") && !n.virtual).length,
    1,
  );
  for (const n of snapshot.nodes) {
    let x: number;
    let y: number;
    if (n.id.startsWith("root:") && !n.virtual) {
      const angle = (2 * Math.PI * rootIndex) / rootCount - Math.PI / 2;
      x = WIDTH / 2 + Math.cos(angle) * 110;
      y = HEIGHT / 2 + Math.sin(angle) * 90;
      rootIndex++;
    } else {
      const angle = (2 * Math.PI * fileIndex) / count + 0.5;
      const radius = 170 + ((fileIndex * 37) % 60);
      x = WIDTH / 2 + Math.cos(angle) * radius;
      y = HEIGHT / 2 + Math.sin(angle) * (radius * 0.7);
      fileIndex++;
    }
    nodes.set(n.id, { id: n.id, x, y, vx: 0, vy: 0, data: n });
  }

  const links = snapshot.edges
    .filter((e) => nodes.has(e.source) && nodes.has(e.target))
    .map((e) => ({ source: e.source, target: e.target }));

  // A few spring iterations to pull linked nodes together.
  for (let step = 0; step < 60; step++) {
    for (const link of links) {
      const a = nodes.get(link.source)!;
      const b = nodes.get(link.target)!;
      const dx = b.x - a.x;
      const dy = b.y - a.y;
      const dist = Math.sqrt(dx * dx + dy * dy) || 1;
      const force = (dist - 130) * 0.02;
      const fx = (dx / dist) * force;
      const fy = (dy / dist) * force;
      a.vx += fx;
      a.vy += fy;
      b.vx -= fx;
      b.vy -= fy;
    }
    // Repulsion pass keeps unlinked nodes apart.
    const list = [...nodes.values()];
    for (let i = 0; i < list.length; i++) {
      for (let j = i + 1; j < list.length; j++) {
        const a = list[i];
        const b = list[j];
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const dist2 = dx * dx + dy * dy;
        if (dist2 < 1 || dist2 > 200 * 200) continue;
        const dist = Math.sqrt(dist2);
        const repulse = (2200 / dist2) * 10;
        const fx = (dx / dist) * repulse;
        const fy = (dy / dist) * repulse;
        a.vx -= fx;
        a.vy -= fy;
        b.vx += fx;
        b.vy += fy;
      }
    }
    for (const node of nodes.values()) {
      if (!node.data.virtual) {
        node.x = Math.min(WIDTH - 30, Math.max(30, node.x + node.vx));
        node.y = Math.min(HEIGHT - 24, Math.max(24, node.y + node.vy));
      }
      node.vx *= 0.85;
      node.vy *= 0.85;
    }
  }

  return { nodes, links };
}

export default function MemoryGraphView({ agentId, onOpenFile }: MemoryGraphViewProps) {
  const { t } = useTranslation();
  const [snapshot, setSnapshot] = useState<MemoryGraphSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<MemoryGraphNode | null>(null);
  const loadSeq = useRef(0);

  useEffect(() => {
    const seq = ++loadSeq.current;
    setLoading(true);
    setError("");
    setSelected(null);
    agentsApi
      .memoryGraph(agentId)
      .then((snap) => {
        if (loadSeq.current !== seq) return;
        setSnapshot(snap);
        setError("");
      })
      .catch((err) => {
        if (loadSeq.current !== seq) return;
        setSnapshot(null);
        setError(err instanceof Error ? err.message : String(err));
      })
      .finally(() => {
        if (loadSeq.current === seq) setLoading(false);
      });
  }, [agentId]);

  const layout = useMemo(
    () => (snapshot ? buildLayout(snapshot) : null),
    [snapshot],
  );

  if (loading) {
    return <div className={styles.graphState}>{t("common.loading")}</div>;
  }
  if (error || !layout) {
    return (
      <div className={styles.graphState} role="alert">
        {error || t("files.memoryGraphLoadFailed")}
      </div>
    );
  }

  const indexedCount = [...layout.nodes.values()].filter(
    (n) => n.data.indexed,
  ).length;

  return (
    <div className={styles.graphWrap}>
      <div className={styles.graphMeta}>
        <span>
          {t("files.memoryGraphCounts", {
            nodes: layout.nodes.size,
            edges: layout.links.length,
          })}
        </span>
        <button
          type="button"
          className={styles.graphBack}
          onClick={() => onOpenFile("digest", "")}
          aria-label={t("common.back")}
        >
          ←
        </button>
      </div>
      <svg
        className={styles.graphSvg}
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-label={t("files.memoryGraph")}
      >
        {layout.links.map((link, i) => {
          const a = layout.nodes.get(link.source)!;
          const b = layout.nodes.get(link.target)!;
          return (
            <line
              key={`${link.source}->${link.target}:${i}`}
              className={styles.graphEdge}
              x1={a.x}
              y1={a.y}
              x2={b.x}
              y2={b.y}
            />
          );
        })}
        {[...layout.nodes.values()].map((node) => (
          <g
            key={node.id}
            className={`${styles.graphNode} ${
              selected?.id === node.id ? styles.graphNodeSelected : ""
            }`}
            transform={`translate(${node.x}, ${node.y})`}
            onClick={() => setSelected(node.data)}
          >
            <circle r={node.id.startsWith("root:") ? 9 : 6} />
            <text>{node.data.name}</text>
          </g>
        ))}
      </svg>
      {selected ? (
        <aside className={styles.graphDetail}>
          <header>
            <strong>{selected.name}</strong>
            <button type="button" onClick={() => setSelected(null)}>
              ✕
            </button>
          </header>
          {selected.description ? <p>{selected.description}</p> : null}
          {selected.indexed ? (
            <button
              type="button"
              className={styles.graphOpen}
              onClick={() =>
                onOpenFile(
                  selected.section === "digest" ? "digest" : "daily",
                  selected.path,
                )
              }
            >
              {t("files.openMemoryFile")}
            </button>
          ) : null}
        </aside>
      ) : null}
      <p className={styles.graphFootnote}>
        {indexedCount} {t("files.memoryIndexedFiles")}
      </p>
    </div>
  );
}
