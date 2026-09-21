(function(){
  const params = new URLSearchParams(location.search);
  const gameId = (params.get('id') || '').trim().toUpperCase();

  const errorBox = document.getElementById('errorBox');
  const roomBar = document.getElementById('roomBar');
  const connDot = document.getElementById('connDot');
  const statusEl = document.getElementById('status');
  const boardEl = document.getElementById('board');
  const logEl = document.getElementById('log');
  const winnerBanner = document.getElementById('winnerBanner');
  const aiBadge = document.getElementById('aiBadge');

  const REMOVAL_ANIM_MS = 480;

  let previousState = null;
  // 自分の操作の応答（fetch）とWebSocket配信が、ほぼ同時に同じ状態を届けることがある。
  // 除外演出中は previousState の更新が480ms後まで遅れるため、それより先に同期的に
  // 更新できる「直近に処理を開始した状態」のキーを別途持ち、重複処理を防ぐ。
  let lastHandledStateKey = null;
  // WebSocketの瞬断や、想定していない例外等によって「AIの手番」表示のまま画面が
  // 固まってしまうことがある（サーバー側は正常に着手済みで、再読み込みすれば直る）。
  // それを自動化するため、AIの手番が一定時間続いたらサーバーへ最新状態を問い合わせて
  // 強制的に描画し直すウォッチドッグを設ける。
  const AI_WATCHDOG_MS = 3000;
  let aiWatchdogTimer = null;

  function clearAiWatchdog(){
    if(aiWatchdogTimer){
      clearTimeout(aiWatchdogTimer);
      aiWatchdogTimer = null;
    }
  }

  function scheduleAiWatchdog(state){
    clearAiWatchdog();
    if(state && state.aiEnabled && !state.gameOver && state.currentPlayer === state.aiColor){
      aiWatchdogTimer = setTimeout(async ()=>{
        try{
          const res = await fetch(`/api/games/${gameId}`);
          if(res.ok){
            const fresh = await res.json();
            // 手数が変わっていなければ強制的に再描画できるよう、重複判定キーを一旦クリアする。
            lastHandledStateKey = null;
            renderWithTransition(fresh);
          }
        }catch(e){
          // 失敗しても次のウォッチドッグや操作で再試行されるため、ここでは何もしない。
        }
      }, AI_WATCHDOG_MS);
    }
  }

  /** previousStateの更新と、AIの手番が固まっていないかのウォッチドッグ再設定をまとめて行う。 */
  function finishRender(state){
    previousState = state;
    scheduleAiWatchdog(state);
  }

  function showError(msg){
    errorBox.innerHTML = `<div class="error-box">${msg}</div>`;
  }

  if(!gameId){
    showError('対局IDが指定されていません。<a href="index.html">ロビー</a>から対局を作成するか、参加してください。');
    statusEl.textContent = '';
    return;
  }

  document.getElementById('roomIdLabel').textContent = gameId;
  roomBar.style.display = '';

  const sfxToggleBtn = document.getElementById('sfxToggleBtn');
  function updateSfxToggleLabel(){
    sfxToggleBtn.textContent = Sfx.isMuted() ? '🔇' : '🔊';
  }
  updateSfxToggleLabel();
  sfxToggleBtn.addEventListener('click', ()=>{
    Sfx.unlock();
    Sfx.setMuted(!Sfx.isMuted());
    updateSfxToggleLabel();
    if(!Sfx.isMuted()) Sfx.click();
  });

  // この端末で操作してよい色（対人戦で「自分の手番でないのに触れてしまう」事故を防ぐ）。
  // ゲームIDごとにブラウザのlocalStorageへ保存する。空文字は「指定しない（従来通り誰でも操作可）」。
  const MY_COLOR_KEY = 'hpgomoku_myColor_' + gameId;
  const myColorBar = document.getElementById('myColorBar');
  let myColor = '';
  try{ myColor = localStorage.getItem(MY_COLOR_KEY) || ''; }catch(e){ /* ignore */ }

  const myColorRadios = document.querySelectorAll('input[name="myColor"]');
  myColorRadios.forEach(radio => {
    radio.checked = (radio.value === myColor);
    radio.addEventListener('change', ()=>{
      myColor = radio.value;
      try{ localStorage.setItem(MY_COLOR_KEY, myColor); }catch(e){ /* ignore */ }
      if(previousState) render(previousState); // 盤面の操作可否を即座に反映
    });
  });

  document.getElementById('copyLinkBtn').addEventListener('click', async ()=>{
    const link = `${location.origin}${location.pathname}?id=${gameId}`;
    try{
      await navigator.clipboard.writeText(link);
      const btn = document.getElementById('copyLinkBtn');
      const original = btn.textContent;
      btn.textContent = 'コピーしました';
      setTimeout(()=>{ btn.textContent = original; }, 1500);
    }catch(e){
      prompt('このリンクを相手に共有してください：', link);
    }
  });

  document.getElementById('resetBtn').addEventListener('click', async ()=>{
    Sfx.unlock();
    Sfx.click();
    try{
      const res = await fetch(`/api/games/${gameId}/reset`, { method:'POST' });
      if(!res.ok) throw await errorFrom(res);
      const state = await res.json();
      // WebSocket配信でも同じ状態が届くが、接続が途切れている場合の保険として自分の応答からも描画する。
      renderWithTransition(state);
    }catch(e){
      showError(e.message || '通信エラーが発生しました。');
    }
  });

  async function errorFrom(res){
    try{
      const body = await res.json();
      return new Error(body.error || `エラー（${res.status}）`);
    }catch(_){
      return new Error(`エラー（${res.status}）`);
    }
  }

  function colorName(c){ return c === 'B' ? '黒' : '白'; }
  function key(r,c){ return r+','+c; }

  async function placeStone(r,c){
    try{
      const res = await fetch(`/api/games/${gameId}/move`, {
        method:'POST',
        headers: { 'Content-Type':'application/json' },
        body: JSON.stringify({ row:r, col:c })
      });
      if(!res.ok) throw await errorFrom(res);
      const state = await res.json();
      // WebSocket配信でも同じ状態が届く（AIの着手を1手ずつ演出するため、通常はそちらが先に反映される）。
      // ただし接続が途切れている等でWebSocketが届かない場合に画面が固まったままにならないよう、
      // 自分の操作の応答からも同じ経路で描画しておく（renderWithTransitionは重複呼び出しに対して安全）。
      renderWithTransition(state);
    }catch(e){
      showError(e.message || '通信エラーが発生しました。');
    }
  }

  function hpPipsHtml(hp, color, justLostCount){
    const total = hp[color] || 0;
    let html = '';
    for(let i=0;i<6;i++){
      if(i < total){
        html += `<div class="hp-pip filled ${color==='B'?'black':'white'}"></div>`;
      } else if(i < total + justLostCount){
        html += `<div class="hp-pip just-lost"></div>`;
      } else {
        html += `<div class="hp-pip"></div>`;
      }
    }
    return html;
  }

  function buildCell(r, c, stone, dmgMarks, removalEchoes, interactive, currentPlayer, extraClass){
    const cell = document.createElement('div');
    cell.className = 'cell' + (extraClass ? ' ' + extraClass : '');
    const k = key(r,c);
    if(stone){
      const s = document.createElement('div');
      s.className = 'stone ' + (stone.color === 'B' ? 'black' : 'white');
      cell.appendChild(s);
      if(stone.dmgFlag){
        const badge = document.createElement('div');
        badge.className = 'stone-dmg-badge';
        badge.title = 'ダメージ増加マークの上の石';
        cell.appendChild(badge);
      }
      if(stone.backAttackBonus > 0){
        const badge2 = document.createElement('div');
        badge2.className = 'stone-back-badge' + (stone.backAttackBonus > 1 ? ' was-dmg' : '');
        badge2.title = 'バックアタックで置かれた石';
        cell.appendChild(badge2);
      }
      cell.classList.add('disabled');
    } else {
      if(dmgMarks.has(k)) cell.classList.add('mark-dmg');
      if(Object.prototype.hasOwnProperty.call(removalEchoes, k)){
        cell.classList.add('mark-echo');
        if(removalEchoes[k]) cell.classList.add('was-dmg');
      }
      if(!interactive){
        cell.classList.add('disabled');
      } else {
        const ghost = document.createElement('div');
        ghost.className = 'ghost ' + (currentPlayer === 'B' ? 'black' : 'white');
        cell.appendChild(ghost);
        cell.addEventListener('click', ()=>{ Sfx.unlock(); placeStone(r,c); });
      }
    }
    return cell;
  }

  function makeLabelCell(text){
    const div = document.createElement('div');
    div.className = 'board-label';
    div.textContent = text;
    return div;
  }

  /** 通常時の盤面描画。boardOverride / extraClasses を渡すと、演出用に一時的な見た目で描画できる。 */
  function renderBoard(state, options){
    options = options || {};
    const board = options.boardOverride || state.board;
    const dmgMarks = new Set(state.dmgMarks || []);
    const removalEchoes = state.removalEchoes || {};
    const interactive = options.interactive !== undefined ? options.interactive
        : !(state.gameOver
            || (state.aiEnabled && state.currentPlayer === state.aiColor)
            || (myColor && state.currentPlayer !== myColor));
    const extraClasses = options.extraClasses || {};

    boardEl.innerHTML = '';

    // 1行目：左上の角（空白）＋ 列ラベル（A〜I）
    boardEl.appendChild(makeLabelCell(''));
    for(let c=0;c<state.size;c++){
      boardEl.appendChild(makeLabelCell(String.fromCharCode(65 + c)));
    }

    for(let r=0;r<state.size;r++){
      boardEl.appendChild(makeLabelCell(String(r + 1))); // 行ラベル（1〜9）
      for(let c=0;c<state.size;c++){
        const cell = buildCell(r, c, board[r][c], dmgMarks, removalEchoes, interactive, state.currentPlayer,
            extraClasses[key(r,c)]);
        boardEl.appendChild(cell);
      }
    }
  }

  function renderMeta(state, options){
    options = options || {};
    const hp = options.hpOverride || state.hp;
    const justLost = options.justLost || {};

    document.getElementById('hpCardB').classList.toggle('active', state.currentPlayer === 'B' && !state.gameOver);
    document.getElementById('hpCardW').classList.toggle('active', state.currentPlayer === 'W' && !state.gameOver);
    document.getElementById('hpBarB').innerHTML = hpPipsHtml(hp, 'B', justLost.B || 0);
    document.getElementById('hpBarW').innerHTML = hpPipsHtml(hp, 'W', justLost.W || 0);

    for(const color of ['B','W']){
      const card = document.getElementById(color === 'B' ? 'hpCardB' : 'hpCardW');
      if(justLost[color] > 0){
        card.classList.remove('hit');
        void card.offsetWidth; // reflowでアニメーションを再トリガー
        card.classList.add('hit');
        const floater = document.createElement('div');
        floater.className = 'dmg-float';
        floater.textContent = '-' + justLost[color];
        card.appendChild(floater);
        setTimeout(()=>floater.remove(), 950);
      }
    }

    if(state.aiEnabled){
      aiBadge.style.display = '';
      aiBadge.textContent = `🤖 AI対戦モード（AI：${colorName(state.aiColor)}）`;
      myColorBar.style.display = 'none'; // AI対戦ではAI側が自動でブロックされるため不要
    } else {
      aiBadge.style.display = 'none';
      myColorBar.style.display = '';
    }

    if(state.gameOver){
      statusEl.innerHTML = 'ゲーム終了。「最初から」で再戦できます。';
    } else if(state.aiEnabled && state.currentPlayer === state.aiColor){
      statusEl.innerHTML = `<span class="turn-of">🤖 AI思考中…</span>`;
    } else {
      const roundNo = Math.ceil((state.plyCount + 1) / 2);
      let s = `<span class="turn-of">${colorName(state.currentPlayer)}の手番</span>（${state.plyCount + 1}手目・${roundNo}巡目）`;
      if(state.pending){
        const pulseClass = options.pendingJustCreated ? ' new' : '';
        s += `　<span class="pending${pulseClass}">保留ダメージ：${colorName(state.pending.target)}に${state.pending.amount}点（反撃/バックアタックで相殺可）</span>`;
      }
      statusEl.innerHTML = s;
    }

    if(state.gameOver){
      if(state.winner === 'draw') winnerBanner.textContent = '引き分け';
      else winnerBanner.textContent = colorName(state.winner) + 'の勝利！';
    } else {
      winnerBanner.textContent = '';
    }

    logEl.innerHTML = (state.log || []).map(l => `<div class="entry">${l}</div>`).join('');
  }

  /** 演出なしの、即時フル描画（初回ロードなどに使う）。 */
  function render(state){
    errorBox.innerHTML = '';
    renderBoard(state);
    renderMeta(state);
  }

  /**
   * 直前の状態との差分を求める。置かれた石・除外されたマスは、盤面の前後比較だけでは
   * 「新しく置いた石自身がその場で除外された」ケース（除外の大半がこれに該当する）を
   * 区別できないため、サーバーが返す newState.lastMove を正として使う。
   */
  function computeDiff(oldState, newState){
    const hpLoss = {};
    for(const color of ['B','W']){
      const before = oldState.hp[color] || 0;
      const after = newState.hp[color] || 0;
      if(after < before) hpLoss[color] = before - after;
    }
    const pendingJustCreated = !oldState.pending && !!newState.pending;
    const wasReset = newState.plyCount === 0 && oldState.plyCount !== 0;

    let placed = null;
    const removed = [];
    const lastMove = newState.lastMove;
    if(lastMove && !wasReset){
      const removedKeys = lastMove.removedCells || [];
      if(removedKeys.length > 0){
        for(const k of removedKeys){
          const [r, c] = k.split(',').map(Number);
          const isPlacedCell = r === lastMove.row && c === lastMove.col;
          const stone = isPlacedCell
              ? { color: lastMove.color, dmgFlag: false, backAttackBonus: 0 }
              : (oldState.board[r][c] || { color: lastMove.color, dmgFlag: false, backAttackBonus: 0 });
          removed.push({ r, c, stone });
        }
      } else {
        placed = { r: lastMove.row, c: lastMove.col, stone: newState.board[lastMove.row][lastMove.col] };
      }
    }
    return { removed, placed, hpLoss, pendingJustCreated, wasReset };
  }

  /**
   * WebSocketで届いた新状態を、直前の状態との差分に応じて演出しながら描画する。
   * 自分の操作の応答（fetch）とWebSocket配信の両方から同じ状態が届くことがあるため、
   * 手数（plyCount）が直前と変わっていなければ何もしない（重複描画・演出の二重再生を防ぐ）。
   */
  function renderWithTransition(newState){
    const stateKey = newState.plyCount + ':' + (newState.gameOver ? 1 : 0);
    if(stateKey === lastHandledStateKey){
      return; // fetchとWebSocketがほぼ同時に同じ状態を届けた（除外演出の完了を待たずに次が来た）
    }
    lastHandledStateKey = stateKey;

    errorBox.innerHTML = '';
    const oldState = previousState;
    if(!oldState){
      render(newState);
      finishRender(newState);
      return;
    }

    try{
      const diff = computeDiff(oldState, newState);

      if(diff.wasReset){
        renderBoard(newState);
        renderMeta(newState, { pendingJustCreated: diff.pendingJustCreated });
        finishRender(newState);
        return;
      }

      const placedClass = placedCellClass(diff.placed);

      if(diff.removed.length === 0){
        const extraClasses = {};
        if(placedClass) extraClasses[key(diff.placed.r, diff.placed.c)] = placedClass;
        renderBoard(newState, { extraClasses });
        renderMeta(newState, { justLost: diff.hpLoss, pendingJustCreated: diff.pendingJustCreated });
        if(placedClass && placedClass.indexOf('back-attack-flash') === 0) Sfx.backAttack();
        else if(diff.placed) Sfx.place();
        if(diff.pendingJustCreated) Sfx.pendingCreated();
        playDamageSfx(diff.hpLoss);
        if(newState.gameOver && !oldState.gameOver) playGameEndSfx(newState);
        finishRender(newState);
        return;
      }

      // 除外が発生：まず新しい石を含む盤面を、消える石だけ復元して「消滅演出」付きで表示する。
      const boardOverride = newState.board.map(row => row.slice());
      const extraClasses = {};
      for(const { r, c, stone } of diff.removed){
        boardOverride[r][c] = stone;
        extraClasses[key(r,c)] = 'removing';
      }
      if(placedClass) extraClasses[key(diff.placed.r, diff.placed.c)] = placedClass;

      renderBoard(newState, { boardOverride, interactive: false, extraClasses });
      renderMeta(oldState, { pendingJustCreated: false });
      Sfx.remove();
      if(diff.pendingJustCreated) Sfx.pendingCreated();

      setTimeout(()=>{
        // 除外演出の完了処理。ここで例外が起きると previousState が更新されず
        // 画面が固まってしまうため、必ず finishRender まで到達するようtry/finallyで守る。
        try{
          renderBoard(newState);
          renderMeta(newState, { justLost: diff.hpLoss, pendingJustCreated: diff.pendingJustCreated });
          playDamageSfx(diff.hpLoss);
          if(newState.gameOver && !oldState.gameOver) playGameEndSfx(newState);
        }catch(e){
          console.error('除外演出の完了処理でエラー', e);
        }finally{
          finishRender(newState);
        }
      }, REMOVAL_ANIM_MS);
    }catch(e){
      console.error('描画処理でエラー', e);
      finishRender(newState);
    }
  }

  function playDamageSfx(hpLoss){
    const amounts = Object.values(hpLoss || {});
    if(amounts.length > 0) Sfx.damage(Math.max(...amounts));
  }

  function playGameEndSfx(state){
    if(state.winner === 'draw') Sfx.draw();
    else Sfx.victory();
  }

  function placedCellClass(placed){
    if(!placed) return null;
    if(placed.stone.backAttackBonus > 0){
      return 'back-attack-flash' + (placed.stone.backAttackBonus > 1 ? ' was-dmg' : '');
    }
    return 'just-placed';
  }

  async function loadInitialState(){
    try{
      const res = await fetch(`/api/games/${gameId}`);
      if(!res.ok) throw await errorFrom(res);
      const state = await res.json();
      render(state);
      finishRender(state);
    }catch(e){
      showError(`対局が見つかりませんでした。IDをご確認のうえ、<a href="index.html">ロビー</a>からやり直してください。`);
      statusEl.textContent = '';
    }
  }

  function connectRealtime(){
    const client = new StompJs.Client({
      webSocketFactory: () => new SockJS('/ws'),
      reconnectDelay: 3000,
      onConnect: () => {
        connDot.classList.add('online');
        client.subscribe(`/topic/games/${gameId}`, (message) => {
          renderWithTransition(JSON.parse(message.body));
        });
      },
      onWebSocketClose: () => {
        connDot.classList.remove('online');
      },
      onStompError: () => {
        connDot.classList.remove('online');
      }
    });
    client.activate();
  }

  loadInitialState();
  connectRealtime();
})();
