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
        cell.addEventListener('click', ()=>placeStone(r,c));
      }
    }
    return cell;
  }

  /** 通常時の盤面描画。boardOverride / extraClasses を渡すと、演出用に一時的な見た目で描画できる。 */
  function renderBoard(state, options){
    options = options || {};
    const board = options.boardOverride || state.board;
    const dmgMarks = new Set(state.dmgMarks || []);
    const removalEchoes = state.removalEchoes || {};
    const interactive = options.interactive !== undefined ? options.interactive
        : !(state.gameOver || (state.aiEnabled && state.currentPlayer === state.aiColor));
    const extraClasses = options.extraClasses || {};

    boardEl.innerHTML = '';
    for(let r=0;r<state.size;r++){
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
    } else {
      aiBadge.style.display = 'none';
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
    errorBox.innerHTML = '';
    const oldState = previousState;
    if(!oldState){
      render(newState);
      previousState = newState;
      return;
    }
    if(newState.plyCount === oldState.plyCount && newState.gameOver === oldState.gameOver){
      return;
    }

    const diff = computeDiff(oldState, newState);

    if(diff.wasReset){
      renderBoard(newState);
      renderMeta(newState, { pendingJustCreated: diff.pendingJustCreated });
      previousState = newState;
      return;
    }

    const placedClass = placedCellClass(diff.placed);

    if(diff.removed.length === 0){
      const extraClasses = {};
      if(placedClass) extraClasses[key(diff.placed.r, diff.placed.c)] = placedClass;
      renderBoard(newState, { extraClasses });
      renderMeta(newState, { justLost: diff.hpLoss, pendingJustCreated: diff.pendingJustCreated });
      previousState = newState;
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

    setTimeout(()=>{
      renderBoard(newState);
      renderMeta(newState, { justLost: diff.hpLoss, pendingJustCreated: diff.pendingJustCreated });
      previousState = newState;
    }, REMOVAL_ANIM_MS);
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
      previousState = state;
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
