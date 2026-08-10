(function () {
  const COOKIE_NAME = 'ai_agent_login';
  const API_BASE_URL = window.AI_AGENT_CONFIG.apiBaseUrl.replace(/\/$/, '');
  const state = {
    userId: '',
    agents: [],
    sessionId: '',
    activeStreamController: null,
  };

  const dom = {
    agentSelect: document.getElementById('agentSelect'),
    agentDescription: document.getElementById('agentDescription'),
    agentTitle: document.getElementById('agentTitle'),
    currentUser: document.getElementById('currentUser'),
    currentSession: document.getElementById('currentSession'),
    messageList: document.getElementById('messageList'),
    emptyState: document.getElementById('emptyState'),
    messageInput: document.getElementById('messageInput'),
    chatForm: document.getElementById('chatForm'),
    sendButton: document.getElementById('sendButton'),
    errorMessage: document.getElementById('errorMessage'),
    statusText: document.getElementById('statusText'),
    logoutButton: document.getElementById('logoutButton'),
    messages: document.getElementById('messages'),
  };

  function getLogin() {
    const item = document.cookie.split('; ').find((entry) => entry.startsWith(`${COOKIE_NAME}=`));
    try {
      if (item) return JSON.parse(decodeURIComponent(item.slice(COOKIE_NAME.length + 1)));
      return JSON.parse(localStorage.getItem(COOKIE_NAME) || 'null');
    } catch { return null; }
  }

  function setStatus(text) { dom.statusText.textContent = text; }
  function showError(message) { dom.errorMessage.textContent = message || ''; }
  function selectedAgent() { return state.agents.find((agent) => agent.agentId === dom.agentSelect.value); }

  function abortActiveStream() {
    const controller = state.activeStreamController;
    state.activeStreamController = null;
    if (controller && !controller.signal.aborted) controller.abort();
  }

  async function request(path, options) {
    const response = await fetch(`${API_BASE_URL}${path}`, options);
    if (!response.ok) throw new Error(`接口请求失败，HTTP ${response.status}`);
    const payload = await response.json();
    if (payload.code !== '0000') throw new Error(payload.info || '服务端处理失败');
    return payload.data;
  }

  function updateAgentInfo() {
    const agent = selectedAgent();
    dom.agentTitle.textContent = agent ? agent.agentName : '智能体对话';
    dom.agentDescription.textContent = agent ? (agent.agentDesc || '该智能体暂未提供描述。') : '请选择一个智能体。';
  }

  function changeAgent() {
    abortActiveStream();
    state.sessionId = '';
    dom.currentSession.textContent = '尚未创建会话';
    updateAgentInfo();
    setStatus('服务已连接');
  }

  function escapeHtml(value) {
    return value.replace(/[&<>"]/g, (character) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[character]));
  }

  function formatInline(value) {
    return escapeHtml(value)
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/\*([^*]+)\*/g, '<em>$1</em>')
      .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>');
  }

  function splitTableRow(line) {
    return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((cell) => cell.trim());
  }

  function isTableDivider(line) {
    return /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line);
  }

  function renderTable(headers, rows) {
    const head = headers.map((cell) => `<th>${formatInline(cell)}</th>`).join('');
    const body = rows.map((row) => {
      const cells = headers.map((_, index) => `<td>${formatInline(row[index] || '')}</td>`).join('');
      return `<tr>${cells}</tr>`;
    }).join('');
    return `<div class="markdown-table-wrap"><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`;
  }

  function renderMarkdown(markdown) {
    const lines = String(markdown || '').replace(/\r\n/g, '\n').split('\n');
    const output = [];
    let listType = null;
    let codeLines = null;

    function closeList() {
      if (listType) output.push(`</${listType}>`);
      listType = null;
    }

    for (let index = 0; index < lines.length; index += 1) {
      const line = lines[index];
      if (line.includes('|') && isTableDivider(lines[index + 1] || '')) {
        closeList();
        const headers = splitTableRow(line);
        const rows = [];
        index += 2;
        while (index < lines.length && lines[index].trim() && lines[index].includes('|')) {
          rows.push(splitTableRow(lines[index]));
          index += 1;
        }
        index -= 1;
        output.push(renderTable(headers, rows));
        continue;
      }
      if (line.startsWith('```')) {
        if (codeLines === null) { closeList(); codeLines = []; }
        else { output.push(`<pre><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`); codeLines = null; }
        continue;
      }
      if (codeLines !== null) { codeLines.push(line); continue; }
      const unordered = line.match(/^[-*+]\s+(.+)$/);
      const ordered = line.match(/^\d+\.\s+(.+)$/);
      if (unordered || ordered) {
        const nextType = unordered ? 'ul' : 'ol';
        if (listType !== nextType) { closeList(); listType = nextType; output.push(`<${listType}>`); }
        output.push(`<li>${formatInline((unordered || ordered)[1])}</li>`);
        continue;
      }
      closeList();
      if (!line.trim()) continue;
      const heading = line.match(/^(#{1,3})\s+(.+)$/);
      if (heading) { const level = heading[1].length; output.push(`<h${level}>${formatInline(heading[2])}</h${level}>`); continue; }
      const quote = line.match(/^>\s?(.+)$/);
      if (quote) { output.push(`<blockquote>${formatInline(quote[1])}</blockquote>`); continue; }
      output.push(`<p>${formatInline(line)}</p>`);
    }
    closeList();
    if (codeLines !== null) output.push(`<pre><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`);
    return output.join('');
  }

  function appendMessage(role, content) {
    dom.emptyState?.remove();
    const row = document.createElement('article');
    row.className = `message-row ${role}`;
    const avatar = document.createElement('div');
    avatar.className = 'avatar';
    avatar.textContent = role === 'user' ? state.userId.slice(0, 2).toUpperCase() : 'AI';
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    if (role === 'agent') bubble.innerHTML = renderMarkdown(content);
    else bubble.textContent = content;
    row.append(avatar, bubble);
    dom.messageList.append(row);
    dom.messages.scrollTop = dom.messages.scrollHeight;
  }

  function createStreamingMessage() {
    dom.emptyState?.remove();

    const row = document.createElement('article');
    row.className = 'message-row agent streaming';

    const avatar = document.createElement('div');
    avatar.className = 'avatar';
    avatar.textContent = 'AI';

    const bubble = document.createElement('div');
    bubble.className = 'bubble stream-bubble';

    const tracePanel = document.createElement('details');
    tracePanel.className = 'trace-panel';
    tracePanel.hidden = true;

    const traceSummary = document.createElement('summary');
    traceSummary.className = 'trace-summary';
    traceSummary.textContent = '查看调研过程（0）';

    const traceList = document.createElement('div');
    traceList.className = 'trace-list';
    tracePanel.append(traceSummary, traceList);

    const answer = document.createElement('div');
    answer.className = 'stream-answer';

    const placeholder = document.createElement('div');
    placeholder.className = 'stream-placeholder';
    placeholder.textContent = '正在等待最终回答…';
    answer.append(placeholder);

    const cursor = document.createElement('span');
    cursor.className = 'stream-cursor';
    cursor.setAttribute('aria-hidden', 'true');

    bubble.append(tracePanel, answer, cursor);
    row.append(avatar, bubble);
    dom.messageList.append(row);

    const traceNodes = new Map();
    let answerContent = '';

    function scrollToLatest() {
      dom.messages.scrollTop = dom.messages.scrollHeight;
    }

    function stopCursor() {
      cursor.remove();
      row.classList.remove('streaming');
    }

    scrollToLatest();

    return {
      updateFinal(content) {
        answerContent = content || '';
        answer.innerHTML = answerContent
          ? renderMarkdown(answerContent)
          : '<div class="stream-placeholder">正在等待最终回答…</div>';
        scrollToLatest();
      },
      upsertTrace(trace) {
        const agentName = trace.agentName || 'Research Agent';
        let traceNode = traceNodes.get(agentName);
        if (!traceNode) {
          const item = document.createElement('section');
          item.className = 'trace-item';
          const title = document.createElement('div');
          title.className = 'trace-agent';
          title.textContent = agentName;
          const content = document.createElement('div');
          content.className = 'trace-content';
          item.append(title, content);
          traceList.append(item);
          traceNode = content;
          traceNodes.set(agentName, traceNode);
        }
        traceNode.innerHTML = renderMarkdown(trace.content || '');
        tracePanel.hidden = false;
        traceSummary.textContent = `查看调研过程（${traceNodes.size}）`;
        scrollToLatest();
      },
      complete() {
        stopCursor();
        scrollToLatest();
      },
      cancel() {
        stopCursor();
        if (!answerContent && traceNodes.size === 0) row.remove();
      },
      fail(message) {
        stopCursor();
        row.classList.add('error');
        if (!answerContent) {
          answer.innerHTML = '';
          const error = document.createElement('div');
          error.className = 'stream-error';
          error.textContent = message;
          answer.append(error);
        } else {
          const error = document.createElement('div');
          error.className = 'stream-error';
          error.textContent = message;
          bubble.append(error);
        }
        scrollToLatest();
      },
    };
  }

  async function createSession(agentId, signal) {
    const data = await request('/create_session', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ agentId, userId: state.userId }),
      signal,
    });
    if (!data?.sessionId) throw new Error('创建会话失败，服务端未返回 sessionId');
    state.sessionId = data.sessionId;
    dom.currentSession.textContent = data.sessionId;
  }

  async function sendMessageStream(agent, message, view, signal) {
    if (!window.AiAgentSse) throw new Error('SSE 客户端未加载');

    const response = await fetch(`${API_BASE_URL}/chat_stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
      body: JSON.stringify({ agentId: agent.agentId, userId: state.userId, sessionId: state.sessionId, message }),
      signal,
    });

    if (!response.ok) {
      const detail = (await response.text()).trim();
      throw new Error(`流式接口请求失败，HTTP ${response.status}${detail ? `：${detail}` : ''}`);
    }

    const streamState = window.AiAgentSse.createAgentStreamState();
    await window.AiAgentSse.consumeJsonSseResponse(response, ({ event, payload }) => {
      const change = streamState.apply(event, payload);
      if (change.kind === 'trace') view.upsertTrace(change.trace);
      if (change.kind === 'answer') view.updateFinal(change.content);
    });

    const snapshot = streamState.snapshot();
    if (!snapshot.hasAnswer) throw new Error('智能体没有返回最终内容');
    return snapshot;
  }

  async function loadAgents() {
    setStatus('加载智能体');
    const agents = await request('/query_ai_agent_config_list', { method: 'GET' });
    if (!Array.isArray(agents) || agents.length === 0) throw new Error('没有可用的智能体配置');
    state.agents = agents;
    dom.agentSelect.innerHTML = '';
    agents.forEach((agent) => {
      const option = document.createElement('option');
      option.value = agent.agentId;
      option.textContent = agent.agentName;
      dom.agentSelect.append(option);
    });
    dom.agentSelect.disabled = false;
    dom.messageInput.disabled = false;
    dom.sendButton.disabled = false;
    updateAgentInfo();
    setStatus('服务已连接');
  }

  function logout() {
    abortActiveStream();
    document.cookie = `${COOKIE_NAME}=; Max-Age=0; Path=/; SameSite=Lax`;
    localStorage.removeItem(COOKIE_NAME);
    window.location.replace('./login.html');
  }

  async function initialise() {
    const login = getLogin();
    if (!login?.user) { window.location.replace('./login.html'); return; }
    state.userId = login.user;
    dom.currentUser.textContent = login.user;
    dom.agentSelect.addEventListener('change', changeAgent);
    dom.logoutButton.addEventListener('click', logout);
    window.addEventListener('pagehide', abortActiveStream);
    window.addEventListener('pageshow', () => {
      if (!state.activeStreamController && state.agents.length > 0) {
        setStatus('服务已连接');
      }
    });
    dom.messageInput.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); dom.chatForm.requestSubmit(); }
    });
    dom.chatForm.addEventListener('submit', async (event) => {
      event.preventDefault();
      const message = dom.messageInput.value.trim();
      if (!message || dom.sendButton.disabled) return;
      showError('');
      appendMessage('user', message);
      dom.messageInput.value = '';
      dom.sendButton.disabled = true;
      setStatus('智能体正在思考');
      const agent = selectedAgent();
      if (!agent) {
        showError('请先选择智能体');
        dom.sendButton.disabled = false;
        return;
      }

      abortActiveStream();
      const controller = new AbortController();
      state.activeStreamController = controller;
      let streamView = null;
      try {
        await createSession(agent.agentId, controller.signal);
        streamView = createStreamingMessage();
        await sendMessageStream(agent, message, streamView, controller.signal);
        streamView.complete();
        setStatus('服务已连接');
      } catch (error) {
        if (error.name === 'AbortError') {
          streamView?.cancel();
        } else {
          if (!controller.signal.aborted) controller.abort();
          const messageText = error.message || '对话请求失败，请稍后重试。';
          streamView?.fail(messageText);
          showError(messageText);
          setStatus('连接异常');
        }
      } finally {
        if (state.activeStreamController === controller) {
          state.activeStreamController = null;
        }
        dom.sendButton.disabled = false;
        dom.messageInput.focus();
      }
    });
    try { await loadAgents(); } catch (error) { showError(error.message || '智能体配置加载失败。'); setStatus('无法连接服务'); }
  }

  initialise();
}());
