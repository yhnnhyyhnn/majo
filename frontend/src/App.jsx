import { useState, useEffect, useRef } from 'react';
import { marked } from 'marked';

marked.setOptions({ breaks: true, gfm: true });

const mdStyle = `
.agent-md h1,.agent-md h2,.agent-md h3 { margin-top:12px; margin-bottom:4px; color:#e0e0e0; }
.agent-md p { margin:4px 0; }
.agent-md ul,.agent-md ol { margin:4px 0; padding-left:20px; }
.agent-md li { margin:2px 0; }
.agent-md strong { color:#f0c674; }
.agent-md em { color:#b5bd68; }
.agent-md a { color:#5dbcd2; }
.agent-md code { font-family:Consolas,monospace; font-size:13px; }
.agent-md pre { margin:8px 0; border-radius:6px; overflow:hidden; border:1px solid #444; }
.agent-md pre code { display:block; background:#1a1a1a; padding:12px 16px; overflow:auto; line-height:1.5; }
.agent-md p code { background:#333; color:#e8a870; padding:2px 5px; border-radius:3px; }
.agent-md blockquote { border-left:3px solid #5dbcd2; margin:8px 0; padding:4px 12px; color:#aaa; }
.agent-md hr { border:none; border-top:1px solid #444; margin:12px 0; }
.agent-md table { border-collapse:collapse; margin:8px 0; font-size:13px; }
.agent-md th,.agent-md td { border:1px solid #444; padding:4px 10px; text-align:left; }
.agent-md th { background:#333; }
`;

const API = '/api';

export default function App() {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [config, setConfig] = useState({
    baseUrl: localStorage.getItem('baseUrl') || 'https://api.openai.com/v1',
    apiKey: localStorage.getItem('apiKey') || '',
    modelName: localStorage.getItem('modelName') || 'gpt-4o-mini',
    port: localStorage.getItem('port') || '18789',
    workspace: localStorage.getItem('workspace') || '',
  });
  const [showConfig, setShowConfig] = useState(false);
  const [sessionId] = useState('session-' + Date.now());
  const bottomRef = useRef(null);

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  const renderMarkdown = (text) => ({ __html: marked.parse(text) });

  const addMsg = (msg) => setMessages(prev => [...prev, { ...msg, id: msg.id ?? Date.now() + Math.random() }]);

  const send = async () => {
    if (!input.trim() || loading) return;
    const prompt = input;
    setInput('');
    setLoading(true);
    addMsg({ role: 'user', content: prompt, time: new Date().toLocaleTimeString() });

    const agentMsgId = Date.now();
    addMsg({ role: 'agent', content: '', thinkingBlocks: [], toolCalls: [], time: '', id: agentMsgId, streaming: true });

    try {
      const res = await fetch(API + '/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt, sessionId, workspace: config.workspace }),
      });

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let agentContent = '';
      let thinkingBlocks = [];
      let currentThinking = null;
      let toolCalls = [];
      let currentTool = null;

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (!line.startsWith('data:')) continue;
          try {
            const data = JSON.parse(line.slice(5).trim());
            if (data.type === 'done') {
              // do nothing, handled by stream end
            } else if (data.type === 'thinking') {
              agentContent += '';
            } else if (data.type === 'ToolCallStartEvent') {
              const d = data.data || {};
              currentTool = { name: d.toolCallName || 'tool', id: d.toolCallId || Date.now(), args: '' };
              toolCalls.push(currentTool);
            } else if (data.type === 'ToolCallDeltaEvent') {
              const d = data.data || {};
              if (currentTool && d.delta) currentTool.args += d.delta;
            } else if (data.type === 'ToolCallEndEvent') {
              currentTool = null;
            } else if (data.type === 'ToolResultStartEvent') {
              const last = toolCalls[toolCalls.length - 1];
              if (last) last.result = '';
            } else if (data.type === 'ToolResultTextDeltaEvent') {
              const d = data.data || {};
              const last = toolCalls[toolCalls.length - 1];
              if (last && d.delta) last.result = (last.result || '') + d.delta;
            } else if (data.type === 'ToolResultEndEvent') {
            } else if (data.type === 'ThinkingBlockStartEvent') {
              currentThinking = { id: Date.now(), text: '' };
              thinkingBlocks.push(currentThinking);
            } else if (data.type === 'ThinkingBlockDeltaEvent') {
              const d = data.data || {};
              if (currentThinking && d.delta) currentThinking.text += d.delta;
            } else if (data.type === 'ThinkingBlockEndEvent') {
              currentThinking = null;
            } else {
              const d = data.data || {};
              if (d.delta) {
                agentContent += d.delta;
              } else if (d.content) {
                agentContent += d.content;
              } else if (d.textContent) {
                agentContent += d.textContent;
              } else if (d.text) {
                agentContent += d.text;
              } else if (d.toolCallName) {
                agentContent += '\n[Tool: ' + d.toolCallName + '] ';
              }
            }

            setMessages(prev => prev.map(m =>
              m.id === agentMsgId ? { ...m, content: agentContent, thinkingBlocks: thinkingBlocks.map(b => ({...b})), toolCalls: toolCalls.map(t => ({...t})) } : m
            ));
          } catch (e) {}
        }
      }
    } catch (e) {
      setMessages(prev => prev.map(m =>
        m.id === agentMsgId ? { ...m, content: 'Error: ' + e.message, streaming: false } : m
      ));
    } finally {
      setMessages(prev => prev.map(m =>
        m.id === agentMsgId ? { ...m, streaming: false } : m
      ));
      setLoading(false);
    }
  };

  const saveConfig = () => {
    Object.entries(config).forEach(([k, v]) => localStorage.setItem(k, v));
    setShowConfig(false);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', maxWidth: 900, margin: '0 auto', width: '100%' }}>
      <style>{mdStyle}</style>
      <div style={{ background: '#222', padding: '8px 16px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderBottom: '1px solid #333' }}>
        <span style={{ fontWeight: 'bold' }}>Majo</span>
        <button onClick={() => setShowConfig(!showConfig)} style={{ background: '#333', color: '#ccc', border: '1px solid #555', padding: '4px 12px', cursor: 'pointer', borderRadius: 3 }}>
          Settings
        </button>
      </div>

      {showConfig && (
        <div style={{ background: '#222', padding: 16, borderBottom: '1px solid #333' }}>
          <div style={{ display: 'grid', gridTemplateColumns: '100px 1fr', gap: 8, maxWidth: 500 }}>
            <span>API URL:</span>
            <input value={config.baseUrl} onChange={e => setConfig({...config, baseUrl: e.target.value})}
              style={inputStyle} />
            <span>API Key:</span>
            <input value={config.apiKey} onChange={e => setConfig({...config, apiKey: e.target.value})} type="password"
              style={inputStyle} />
            <span>Model:</span>
            <input value={config.modelName} onChange={e => setConfig({...config, modelName: e.target.value})}
              style={inputStyle} />
            <span>Workspace:</span>
            <input value={config.workspace} onChange={e => setConfig({...config, workspace: e.target.value})}
              placeholder="e.g. D:\projects\my-app"
              style={inputStyle} />
          </div>
          <button onClick={saveConfig} style={{ background: '#3264c8', color: 'white', border: 'none', padding: '6px 16px', marginTop: 10, cursor: 'pointer', borderRadius: 3 }}>
            Save
          </button>
          <span style={{ color: '#888', marginLeft: 10, fontSize: 12 }}>保存后需修改 backend 的 application.properties 并重启</span>
        </div>
      )}

      <div style={{ flex: 1, overflow: 'auto', padding: 16 }}>
        {messages.map(m => (
          <div key={m.id} style={{
            display: 'flex',
            flexDirection: m.role === 'user' ? 'row-reverse' : 'row',
            marginBottom: 20,
            gap: 10,
            alignItems: 'flex-start',
          }}>
            <div style={{
              width: 36, height: 36, borderRadius: '50%',
              background: m.role === 'user' ? '#3264c8' : '#2a8',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontSize: 18, flexShrink: 0,
            }}>
              {m.role === 'user' ? '👤' : '🤖'}
            </div>
            <div style={{
              maxWidth: '75%',
              background: m.role === 'user' ? '#3264c8' : '#2a2a2a',
              color: m.role === 'user' ? '#fff' : '#e0e0e0',
              padding: '12px 16px',
              borderRadius: 12,
              borderTopRightRadius: m.role === 'user' ? 4 : 12,
              borderTopLeftRadius: m.role === 'user' ? 12 : 4,
            }}>
              <div style={{ color: m.role === 'user' ? 'rgba(255,255,255,0.6)' : '#888', fontSize: 11, marginBottom: 6 }}>
                {m.role === 'user' ? 'You' : 'Agent'} · {m.time}
                {m.streaming && <span style={{ color: '#2a8', marginLeft: 8 }}>● streaming</span>}
              </div>
              {m.thinkingBlocks?.length > 0 && (
                <div style={{ marginBottom: 6 }}>
                  {m.thinkingBlocks.map((b, i) => (
                    <div key={b.id || i} style={{
                      color: '#999', fontSize: 12, marginBottom: 6, whiteSpace: 'pre-wrap',
                      fontStyle: 'italic', borderLeft: '2px solid #555', padding: '4px 0 4px 10px',
                      background: 'rgba(255,255,255,0.02)', borderRadius: '0 4px 4px 0',
                    }}>
                      {b.text}
                    </div>
                  ))}
                </div>
              )}
              <div className="agent-md" style={{ lineHeight: 1.6, wordBreak: 'break-word' }}>
                {m.content
                  ? m.role === 'user'
                    ? <span style={{ whiteSpace: 'pre-wrap' }}>{m.content}</span>
                    : <div dangerouslySetInnerHTML={renderMarkdown(m.content)} />
                  : m.streaming
                    ? 'Thinking...'
                    : ''
                }
              </div>
              {m.toolCalls?.length > 0 && (
                <div style={{ marginTop: 10, borderTop: '1px solid #444', paddingTop: 8 }}>
                  {m.toolCalls.map((tc, i) => (
                    <div key={tc.id || i} style={{
                      background: '#1e1e1e', border: '1px solid #444', borderRadius: 6,
                      padding: '8px 12px', marginBottom: 6, fontSize: 13,
                    }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2 }}>
                        <span style={{ color: '#f0c674' }}>🔧</span>
                        <span style={{ color: '#f0c674', fontWeight: 500 }}>{tc.name}</span>
                        {tc.result == null && <span style={{ color: '#888', fontSize: 11 }}>executing...</span>}
                      </div>
                      {tc.args && (
                        <div style={{ color: '#aaa', fontSize: 12, fontFamily: 'Consolas,monospace', opacity: 0.8 }}>
                          {tc.args.length > 120 ? tc.args.slice(0, 120) + '...' : tc.args}
                        </div>
                      )}
                      {tc.result != null && (
                        <div style={{ color: '#8abeb7', fontSize: 12, fontFamily: 'Consolas,monospace', marginTop: 4,
                          maxHeight: 120, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                          {tc.result.length > 300 ? tc.result.slice(0, 300) + '...' : tc.result}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>

      <div style={{ padding: '8px 16px', borderTop: '1px solid #333', background: '#222' }}>
        <div style={{ display: 'flex', gap: 8 }}>
          <input value={input} onChange={e => setInput(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
            placeholder="输入任务描述，Enter 发送..."
            disabled={loading}
            style={{ ...inputStyle, flex: 1 }} />
          <button onClick={send} disabled={loading || !input.trim()}
            style={{ background: loading ? '#444' : '#3264c8', color: 'white', border: 'none', padding: '6px 20px', cursor: loading ? 'not-allowed' : 'pointer', borderRadius: 3 }}>
            Send
          </button>
        </div>
      </div>
    </div>
  );
}

const inputStyle = {
  background: '#111', color: '#ddd', border: '1px solid #444', padding: '6px 10px', borderRadius: 3, fontFamily: 'Consolas, monospace', fontSize: 13
};
