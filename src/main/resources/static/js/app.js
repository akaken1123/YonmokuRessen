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
      render(state);
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
      render(state);
    }catch(e){
      showError(e.message || '通信エラーが発生しました。');
    }
  }

  function hpPips(state, color){
    let html = '';
    for(let i=0;i<6;i++){
      const filled = i < (state.hp[color] || 0);
      html += `<div class="hp-pip ${filled ? 'filled '+(color==='B'?'black':'white') : ''}"></div>`;
    }
    return html;
  }

  function render(state){
    errorBox.innerHTML = '';
    const dmgMarks = new Set(state.dmgMarks || []);
    const removalEchoes = state.removalEchoes || {};

    boardEl.innerHTML = '';
    for(let r=0;r<state.size;r++){
      for(let c=0;c<state.size;c++){
        const cell = document.createElement('div');
        cell.className = 'cell';
        const k = key(r,c);
        const stone = state.board[r][c];
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
          const aiTurn = state.aiEnabled && state.currentPlayer === state.aiColor;
          if(state.gameOver || aiTurn){
            cell.classList.add('disabled');
          } else {
            const ghost = document.createElement('div');
            ghost.className = 'ghost ' + (state.currentPlayer === 'B' ? 'black' : 'white');
            cell.appendChild(ghost);
            cell.addEventListener('click', ()=>placeStone(r,c));
          }
        }
        boardEl.appendChild(cell);
      }
    }

    document.getElementById('hpCardB').classList.toggle('active', state.currentPlayer === 'B' && !state.gameOver);
    document.getElementById('hpCardW').classList.toggle('active', state.currentPlayer === 'W' && !state.gameOver);
    document.getElementById('hpBarB').innerHTML = hpPips(state, 'B');
    document.getElementById('hpBarW').innerHTML = hpPips(state, 'W');

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
        s += `　<span class="pending">保留ダメージ：${colorName(state.pending.target)}に${state.pending.amount}点（反撃/バックアタックで相殺可）</span>`;
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

  async function loadInitialState(){
    try{
      const res = await fetch(`/api/games/${gameId}`);
      if(!res.ok) throw await errorFrom(res);
      const state = await res.json();
      render(state);
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
          render(JSON.parse(message.body));
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
