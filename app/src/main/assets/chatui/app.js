(function () {
  const { createApp, h, ref, computed, nextTick } = Vue;
  const UI = AiChatUi;

  const chat = ref({
    messages: [], sessions: [], sessionId: '', title: '新对话', busy: false,
    model: '未配置模型', pendingImages: [], profiles: [], activeProfileId: '',
    contextText: '', tools: []
  });
  const drawer = ref(false);
  const modelMenu = ref(false);
  const toolsPanel = ref(false);
  const toolsLoading = ref(false);
  /** 回形针的二级菜单：上传图片 / 上传文件。 */
  const attachMenu = ref(false);
  const notice = ref('');
  let noticeTimer = 0;

  function tell(text) {
    notice.value = text || '';
    clearTimeout(noticeTimer);
    if (text) noticeTimer = setTimeout(() => notice.value = '', 2800);
  }

  function native(name, ...args) {
    const api = window.SuperAohNative;
    if (!api || typeof api[name] !== 'function') return false;
    try { return api[name](...args); } catch (_) { return false; }
  }

  /**
   * system 角色在后端用于「错误/提示」。
   * 交给组件时带上 error 状态，比普通正文更显眼。
   */
  function formatMessage(m) {
    if (m.role !== 'system') return m;
    return { ...m, role: 'assistant', status: 'error', error: true };
  }

  const messageList = computed(() => chat.value.messages.map(formatMessage));
  const items = computed(() => chat.value.sessions);

  /** 上下文用量：后端只给一段文本，这里解析成数字交给组件画进度。 */
  const contextUsage = computed(() => {
    const text = String(chat.value.contextText || '');
    const m = text.match(/([\d.]+)\s*\/\s*([\d.]+)/);
    if (!m) return null;
    const used = parseFloat(m[1]);
    const total = parseFloat(m[2]);
    if (!isFinite(used) || !isFinite(total) || total <= 0) return null;
    return { used, total, ratio: Math.min(1, used / total) };
  });

  /**
   * 发送。
   *
   * 附件不走 payload.files：组件的上传通道已经关闭（见 uploadConfig），
   * 附件全部由原生侧 addImageData / chooseFile 直接加进 pendingXxx，
   * 这里只需要把文字发过去，原生 send() 会自己带上待发送的附件。
   */
  async function onSend(payload) {
    const text = (payload && payload.text) || '';
    native('send', text);
  }

  const app = createApp({
    setup() {
      function selectPreset(p) { native('send', p.prompt); }
      function clickSession(id) { drawer.value = false; native('openSession', id); }
      function createSession() { drawer.value = false; native('newSession'); }
      function removeSession(id) {
        const item = chat.value.sessions.find(s => s.id === id);
        if (window.confirm('删除会话「' + (item ? item.title : '') + '」？此操作不可撤销。')) native('deleteSession', id);
      }
      function renameSession(id, title) { native('renameSession', id, title); }
      function chooseProfile(id) { modelMenu.value = false; native('selectProfile', id); }
      function openSettings() { native('openSettings'); }
      function capture() { native('captureScreen'); }
      function removeImage(index) { native('removeImage', index); }
      function removeFile(index) { native('removeFile', index); }
      function retry(m) { native('retry', m && m.id); }

      /**
       * 打开系统文件选择器。
       *
       * 用原生 ACTION_OPEN_DOCUMENT 而不是 <input type=file>：
       * WebView 的文件选择走 onShowFileChooser，需要额外实现，
       * 且拿到的只是临时 URI，没法保证大文件流式复制。
       * 原生侧直接 openInputStream → 复制到会话目录，全程 256KB 缓冲。
       */
      function pickFile() {
        attachMenu.value = false;
        native('chooseFile');
      }

      function pickImage() {
        attachMenu.value = false;
        native('chooseImage');
      }

      function openTools() {
        toolsPanel.value = true;
        if ((chat.value.tools || []).length) return;
        toolsLoading.value = true;
        native('listTools');
        // 原生侧异步回填；给个兜底，避免面板一直显示「读取中」
        setTimeout(() => { toolsLoading.value = false; }, 2500);
      }

      function groupTools(list) {
        const groups = new Map();
        for (const t of (list || [])) {
          const key = t.source || '内置';
          if (!groups.has(key)) groups.set(key, []);
          groups.get(key).push(t);
        }
        return Array.from(groups.entries()).map(([source, tools]) => ({ source, tools }));
      }

      return () => {
        const toolbar = h('header', { class: 'top' }, [
          h('button', { class: 'iconbtn', title: '会话列表', onClick: () => drawer.value = !drawer.value }, '☰'),
          h('div', { class: 'brand' }, [
            h('span', { class: 'brand-name' }, 'SuperAoh'),
            h('span', { class: 'brand-sub' }, chat.value.title || '新对话')
          ]),
          h('button', {
            class: 'iconbtn chip',
            title: '切换模型',
            onClick: () => modelMenu.value = !modelMenu.value
          }, chat.value.model || '未配置模型'),
          h('button', { class: 'iconbtn', title: 'AI 可调用的工具', onClick: openTools }, '工具'),
          h('button', { class: 'iconbtn', title: '设置', onClick: openSettings }, '⚙'),
          (window.SuperAohNative && window.SuperAohNative.isGameMode())
            ? h('button', {
                class: ['iconbtn', 'chip', chat.value.controlsEnabled ? 'authorized' : ''],
                title: '授权 AI 执行游戏点击',
                onClick: () => native('toggleGameControl', !chat.value.controlsEnabled)
              }, chat.value.controlsEnabled ? '控制已开' : 'AI 控制关')
            : null,
          h('button', { class: 'iconbtn', title: '关闭', onClick: () => native('close') }, '×')
        ]);

        const drawerView = drawer.value ? h('div', {
          class: 'drawer-backdrop', onClick: () => drawer.value = false
        }, [
          h('aside', { class: 'drawer', onClick: e => e.stopPropagation() }, [
            h('div', { class: 'drawer-head' }, [
              h('strong', '会话'),
              h('button', { class: 'iconbtn', onClick: createSession }, '+ 新建')
            ]),
            h(UI.ConversationList, {
              items: items.value,
              activeId: chat.value.sessionId,
              showSearch: true,
              showNew: false,
              compact: true,
              onSelect: id => clickSession(id),
              onDelete: id => removeSession(id),
              onRename: (id, title) => renameSession(id, title),
              onNew: createSession
            })
          ])
        ]) : null;

        const profiles = modelMenu.value ? h('div', {
          class: 'model-backdrop', onClick: () => modelMenu.value = false
        }, [
          h('div', { class: 'model-menu', onClick: e => e.stopPropagation() }, [
            h('div', { class: 'drawer-head' }, [
              h('strong', '模型'),
              h('button', { class: 'iconbtn', onClick: () => modelMenu.value = false }, '×')
            ]),
            ...(chat.value.profiles || []).map(p => h('button', {
              class: ['profile-row', p.id === chat.value.activeProfileId ? 'selected' : ''],
              onClick: () => chooseProfile(p.id)
            }, [h('span', p.name), h('small', p.model)])),
            !(chat.value.profiles || []).length ? h('div', { class: 'empty-small' }, '尚未配置模型') : null
          ])
        ]) : null;

        // ---- 工具清单面板 ----
        const toolRows = [];
        for (const g of groupTools(chat.value.tools)) {
          toolRows.push(h('div', { class: 'tool-group-title' }, g.source + ' · ' + g.tools.length));
          for (const t of g.tools) {
            const params = (t.params || []).join(', ');
            toolRows.push(h('div', { class: 'tool-item' }, [
              h('div', { class: 'tool-name' }, [
                h('code', t.name),
                t.needsWorkspace ? h('span', { class: 'tool-tag' }, '需工作区') : null
              ]),
              h('div', { class: 'tool-desc' }, t.description || ''),
              params ? h('div', { class: 'tool-params' }, '参数：' + params) : null
            ]));
          }
        }
        const toolsView = toolsPanel.value ? h('div', {
          class: 'model-backdrop', onClick: () => toolsPanel.value = false
        }, [
          h('div', { class: 'tools-menu', onClick: e => e.stopPropagation() }, [
            h('div', { class: 'drawer-head' }, [
              h('strong', 'AI 可调用工具' + ((chat.value.tools || []).length ? ' · ' + chat.value.tools.length : '')),
              h('button', { class: 'iconbtn', onClick: () => toolsPanel.value = false }, '×')
            ]),
            h('div', { class: 'tools-hint' }, '这些工具会随每次请求一起提交给模型。工具越多，前缀 token 消耗越大。'),
            h('div', { class: 'tools-body' }, toolsLoading.value && !toolRows.length
              ? [h('div', { class: 'empty-small' }, '读取中…')]
              : (toolRows.length ? toolRows : [h('div', { class: 'empty-small' }, '暂无可用工具')]))
          ])
        ]) : null;

        const attachments = ((chat.value.pendingImages || []).length || (chat.value.pendingFiles || []).length)
          ? h('div', { class: 'attach-row' }, [
              ...(chat.value.pendingImages || []).map((img, i) =>
                h('button', { class: 'attach', onClick: () => removeImage(i), title: '移除图片' }, [
                  h('img', { src: img.preview, alt: '', width: 30, height: 30 }),
                  h('span', img.name || '图片'), h('b', '×')
                ])
              ),
              ...(chat.value.pendingFiles || []).map((f, i) =>
                h('button', { class: 'attach', onClick: () => removeFile(i), title: '移除文件' }, [
                  h('span', { class: 'attach-fileicon' }, '📄'),
                  h('span', { class: 'attach-filename' }, f.name || '文件'),
                  h('small', f.sizeText || ''),
                  h('b', '×')
                ])
              )
            ])
          : null;

        const chatProps = {
          messages: messageList.value,
          theme: 'dark',
          assistantName: 'SuperAoh',
          showAvatar: false,
          generating: chat.value.busy,
          disabled: false,
          allowQueue: false,
          model: chat.value.model || '',
          contextUsage: contextUsage.value,
          welcomeTitle: '文明时代 II 助手',
          welcomeDescription: '可以分析游戏状态、查阅工作区资料、反编译 APK，或协助进行游戏内操作。',
          presetQuestions: [
            { id: 'state', label: '查看游戏状态', prompt: '读取当前游戏运行状态，并说明我现在所在的界面。' },
            { id: 'strategy', label: '给我一条战略建议', prompt: '根据当前游戏状态，给我一条可执行的战略建议。先检查游戏状态。' },
            { id: 'reverse', label: '反编译这个 APK', prompt: '先调用 toolchain_status 查看反编译工具链状态，然后用 apktool_decode 解包工作区里的游戏 APK。' },
            { id: 'help', label: '游戏内怎么操作？', prompt: '说明当前游戏画面的主要操作方式，并先读取游戏运行状态。' }
          ],
          placeholder: '询问游戏、让它反编译，或直接下指令…',
          /**
           * 关掉组件自带的回形针。
           *
           * 它只能按 accept 收一类文件，且拿到的是 File 对象，
           * 塞进请求体就等于把整个文件读进内存——大文件必然 OOM。
           * 附件统一走下方 composer-tools 里的「📎 附件」二级菜单，
           * 由原生侧流式复制到会话目录，格式和大小都不受限。
           */
          uploadConfig: { enabled: false },
          toolCallsConfig: { group: true, collapseThreshold: 2, defaultExpanded: false },
          thinkingConfig: { scrollable: true, maxHeight: 150, followStream: true },
          actionsConfig: { enable: true, copy: true, regenerate: false },
          messageRailConfig: { enable: false },
          /**
           * 气泡尾部元信息：token 用量 / 首字延迟 / 耗时。
           *
           * 数据来自后端 snapshot 的 message.meta，字段名必须与
           * zen-ai-chat-ui 的 MessageMeta 组件一致（usage.prompt/completion…）。
           * visibility:'always' 而不是默认的 'hover'：
           * 触屏没有 hover，默认值会让这行永远不显示。
           */
          messageMetaConfig: {
            enable: true,
            // 组件按 `is-meta-${position}` 生成类名，CSS 里只定义了 is-meta-below，
            // 写成 'under' 会退化成一列堆叠且失去对齐，必须是 'below'。
            position: 'below',
            visibility: 'always',
            items: ['tokens', 'firstToken', 'duration']
          },
          onSend,
          onStop: () => native('cancel'),
          onSelect: selectPreset,
          onRetry: retry,
          onFollowupSelect: question => native('send', question.prompt)
        };

        const compose = h('section', { class: 'chat-area' }, [
          h(UI.ChatContainer, chatProps),
          attachments,
          h('div', { class: 'composer-tools' }, [
            h('button', {
              class: ['toolbtn', attachMenu.value ? 'on' : ''],
              title: '添加附件',
              onClick: () => attachMenu.value = !attachMenu.value
            }, '📎 附件'),
            (window.SuperAohNative && window.SuperAohNative.isGameMode())
              ? h('button', { class: 'toolbtn', title: '截取游戏画面并作为图片附加', onClick: capture }, '◉ 游戏画面')
              : null,
            h('button', {
              class: 'toolbtn model',
              title: '点击切换模型',
              onClick: () => modelMenu.value = !modelMenu.value
            }, '⚙ ' + (chat.value.model || '未配置模型')),
            h('span', { class: 'context' }, chat.value.contextText || '')
          ]),
          // 二级菜单：从输入栏上方弹出，避免挡住已输入的文字
          attachMenu.value ? h('div', {
            class: 'attach-backdrop', onClick: () => attachMenu.value = false
          }, [
            h('div', { class: 'attach-menu', onClick: e => e.stopPropagation() }, [
              h('button', { class: 'attach-item', onClick: pickImage }, [
                h('span', { class: 'attach-icon' }, '🖼'),
                h('span', { class: 'attach-text' }, [
                  h('strong', '上传图片'),
                  h('small', '会压缩到 1280px，适合让模型看图')
                ])
              ]),
              h('button', { class: 'attach-item', onClick: pickFile }, [
                h('span', { class: 'attach-icon' }, '📄'),
                h('span', { class: 'attach-text' }, [
                  h('strong', '上传文件'),
                  h('small', '不限格式、不限大小，模型按需读取')
                ])
              ])
            ])
          ]) : null
        ]);

        return h('main', { class: 'shell' }, [
          toolbar,
          notice.value ? h('div', { class: 'toast' }, notice.value) : null,
          compose, drawerView, profiles, toolsView
        ]);
      };
    }
  });

  app.mount('#app');

  window.SuperAoh = {
    receive(type, payload) {
      if (type === 'snapshot') {
        chat.value = { ...chat.value, ...payload };
        nextTick(() => {
          const root = document.querySelector('.acu-scroll-container, .acu-message-list, .messages');
          if (root && chat.value.busy) root.scrollTop = root.scrollHeight;
        });
      } else if (type === 'tools') {
        toolsLoading.value = false;
        chat.value = { ...chat.value, tools: (payload && payload.tools) || [] };
      } else if (type === 'delta') {
        const i = chat.value.messages.findIndex(m => String(m.id) === String(payload.id));
        if (i >= 0) {
          const rows = chat.value.messages.slice();
          const row = { ...rows[i] };
          row[payload.kind] = (row[payload.kind] || '') + payload.delta;
          rows[i] = row;
          chat.value = { ...chat.value, messages: rows };
        }
      } else if (type === 'notice') {
        tell(payload.text);
      }
    },
    back() {
      if (attachMenu.value) { attachMenu.value = false; return true; }
      if (toolsPanel.value) { toolsPanel.value = false; return true; }
      if (modelMenu.value) { modelMenu.value = false; return true; }
      if (drawer.value) { drawer.value = false; return true; }
      return false;
    }
  };

  const theme = document.createElement('style');
  theme.textContent = `
    #app .shell > .chat-area { flex:1;min-height:0;display:flex;flex-direction:column;position:relative; }
    #app .chat-area > .acu-root, #app .chat-area > .acu-chat-container { flex:1;min-height:0; }
    #app .acu-root { flex:1;min-height:0;overflow:hidden; }
    #app .acu-root .acu-chat-container { height:100%; }
    #app .acu-root .acu-input { padding-bottom:62px!important; }
    #app .composer-tools { position:absolute;z-index:5;bottom:9px;left:17px;right:14px;height:34px;display:flex;align-items:center;gap:8px;pointer-events:none; }
    #app .composer-tools > * { pointer-events:auto; }
    #app .toolbtn { border:0;background:transparent;color:#aab9d8;font-size:11px;padding:7px 8px;border-radius:8px; }
    #app .toolbtn:active { background:#202c40; }
    #app .toolbtn.on { background:#233451;color:#c9d7ff; }
    /* 模型切换按钮：和上下文计数挤在一行，必须能被截断，否则窄屏会溢出去 */
    #app .toolbtn.model { max-width:42vw;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#a9bcff; }
    #app .context { margin-left:auto;color:#73819c;font-size:10px;white-space:nowrap; }

    /* ---- 附件的二级菜单 ---- */
    #app .attach-backdrop { position:fixed;inset:0;z-index:22;background:rgba(0,0,0,.42);display:flex;align-items:flex-end;justify-content:center;padding-bottom:64px; }
    #app .attach-menu { width:min(360px,90vw);background:#111b2a;border:1px solid rgba(180,200,235,.18);border-radius:14px;padding:8px;display:flex;flex-direction:column;gap:4px;box-shadow:0 18px 48px rgba(0,0,0,.5); }
    #app .attach-item { display:flex;align-items:center;gap:11px;width:100%;padding:12px 11px;border:0;border-radius:10px;background:transparent;color:#e6ecff;text-align:left; }
    #app .attach-item:active { background:#233451; }
    #app .attach-icon { font-size:19px;width:24px;text-align:center;flex:0 0 auto; }
    #app .attach-text { display:flex;flex-direction:column;gap:2px;min-width:0; }
    #app .attach-text strong { font-size:13px;font-weight:650; }
    #app .attach-text small { font-size:10.5px;color:#8b99b4; }

    /* ---- 待发送附件条 ---- */
    #app .attach-row { position:absolute;z-index:6;bottom:52px;left:16px;right:14px;display:flex;flex-wrap:wrap;gap:6px; }
    #app .attach { display:flex;align-items:center;gap:6px;border:1px solid rgba(180,200,235,.14); }
    #app .attach img { object-fit:cover;border-radius:6px; }
    #app .attach-filename { max-width:34vw;overflow:hidden;text-overflow:ellipsis;white-space:nowrap; }
    #app .attach small { color:#8b99b4;font-size:10px; }
    #app .attach-fileicon { font-size:14px; }
    #app .drawer-backdrop,#app .model-backdrop { position:fixed;inset:0;z-index:20;background:rgba(0,0,0,.52);display:flex;align-items:stretch; }
    #app .drawer { width:min(340px,86vw);height:100%;background:#101827;border-right:1px solid rgba(180,200,235,.14);padding:14px;display:flex;flex-direction:column;gap:12px;overflow:hidden; }
    #app .drawer-head { display:flex;align-items:center;justify-content:space-between;min-height:38px;color:#edf2ff;gap:8px; }
    #app .drawer .acu-conversation-list { flex:1;min-height:0;overflow:auto; }
    #app .model-backdrop { justify-content:flex-end;align-items:flex-start;padding:58px 10px 0; }
    #app .model-menu { width:min(340px,90vw);max-height:75vh;overflow:auto;background:#111b2a;border:1px solid rgba(180,200,235,.16);border-radius:14px;padding:12px;box-shadow:0 18px 48px rgba(0,0,0,.35); }
    #app .profile-row { width:100%;display:flex;flex-direction:column;align-items:flex-start;gap:3px;padding:10px;border:0;border-radius:9px;background:transparent;color:#e5ebfc;text-align:left; }
    #app .profile-row small { color:#96a4c0;font-size:11px; }
    #app .profile-row.selected { background:#233451; }
    #app .empty-small { color:#8f9db7;padding:16px 6px;font-size:13px; }
    #app .toast { position:fixed;z-index:30;left:50%;top:60px;transform:translateX(-50%);max-width:85%;padding:9px 13px;background:#26354c;color:#eef2ff;border:1px solid rgba(180,200,235,.2);border-radius:10px;font-size:12px;box-shadow:0 5px 20px #0005; }
    .acu-root [class*=message-content] { overflow-wrap:anywhere; }

    /* ---- 顶栏层次 ---- */
    #app .brand { display:flex;flex-direction:column;line-height:1.15;min-width:0;flex:1; }
    #app .brand-name { font-weight:750;font-size:14px;letter-spacing:.02em; }
    #app .brand-sub { font-size:10px;color:#7c8aa5;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:100%; }
    #app .chip { max-width:24vw;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#a9bcff;border-color:rgba(143,169,255,.28); }
    #app .authorized { color:#7ef0b0;border-color:rgba(126,240,176,.45);background:rgba(38,120,86,.28); }

    /* ---- 工具清单面板 ---- */
    #app .tools-menu { width:min(560px,94vw);max-height:82vh;display:flex;flex-direction:column;background:#0f1826;border:1px solid rgba(180,200,235,.18);border-radius:14px;padding:12px;box-shadow:0 20px 56px rgba(0,0,0,.45); }
    #app .tools-hint { color:#7f8da8;font-size:11px;line-height:1.55;padding:2px 2px 10px; }
    #app .tools-body { flex:1;min-height:0;overflow:auto;display:flex;flex-direction:column;gap:8px;padding-right:2px; }
    #app .tool-group-title { color:#8fa9ff;font-size:11px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;padding:8px 2px 2px;position:sticky;top:0;background:#0f1826; }
    #app .tool-item { border:1px solid rgba(180,200,235,.11);background:#131d2c;border-radius:10px;padding:9px 10px; }
    #app .tool-name { display:flex;align-items:center;gap:7px;flex-wrap:wrap; }
    #app .tool-name code { color:#c9d7ff;font-size:12px;font-family:ui-monospace,monospace;word-break:break-all; }
    #app .tool-tag { font-size:9px;color:#0b1019;background:#8fa9ff;border-radius:5px;padding:1px 5px;font-weight:700; }
    #app .tool-desc { color:#9dabc4;font-size:11.5px;line-height:1.55;margin-top:5px; }
    #app .tool-params { color:#6f7d96;font:10.5px/1.5 ui-monospace,monospace;margin-top:5px;word-break:break-all; }

    /* ---- 窄屏收紧 ---- */
    @media(max-width:420px) {
      #app .top { padding:0 8px;gap:5px; }
      #app .chip { max-width:18vw;font-size:11px; }
      #app .iconbtn { padding:6px 7px;font-size:11px; }
      #app .brand-sub { display:none; }
    }
  `;
  document.head.appendChild(theme);

  window.addEventListener('load', () => {
    if (window.SuperAohNative && window.SuperAohNative.onReady) window.SuperAohNative.onReady();
  });
})();