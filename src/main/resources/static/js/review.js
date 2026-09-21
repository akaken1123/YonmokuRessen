(function(){
  const params = new URLSearchParams(location.search);
  const gameId = (params.get('id') || '').trim().toUpperCase();

  const errorBox = document.getElementById('errorBox');
  const boardEl = document.getElementById('board');
  const statusEl = document.getElementById('status');
  const moveListEl = document.getElementById('moveList');
  const moveIndicator = document.getElementById('moveIndicator');

  function showError(msg){
    errorBox.innerHTML = `<div class="error-box">${msg}</div>`;
  }

  if(!gameId){
    showError('対局IDが指定されていません。<a href="index.html">ロビー</a>から確認してください。');
    statusEl.textContent = '';
    return;
  }

  document.getElementById('reviewGameId').textContent = gameId;
  document.getElementById('backToGameLink').href = `game.html?id=${gameId}`;

  function colorName(c){ return c === 'B' ? '黒' : '白'; }
  function key(r,c){ return r+','+c; }

  let history = [];
  let index = 0; // 表示中の手（0始まり。0なら1手目の直後）

  function makeLabelCell(text){
    const div = document.createElement('div');
    div.className = 'board-label';
    div.textContent = text;
    return div;
  }

  function buildCell(stone, dmgMarks, removalEchoes, k){
    const cell = document.createElement('div');
    cell.className = 'cell disabled';
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
    } else {
      if(dmgMarks.has(k)) cell.classList.add('mark-dmg');
      if(Object.prototype.hasOwnProperty.call(removalEchoes, k)){
        cell.classList.add('mark-echo');
        if(removalEchoes[k]) cell.classList.add('was-dmg');
      }
    }
    return cell;
  }

  function renderBoard(state){
    const dmgMarks = new Set(state.dmgMarks || []);
    const removalEchoes = state.removalEchoes || {};
    boardEl.innerHTML = '';
    boardEl.appendChild(makeLabelCell(''));
    for(let c=0;c<state.size;c++){
      boardEl.appendChild(makeLabelCell(String.fromCharCode(65 + c)));
    }
    for(let r=0;r<state.size;r++){
      boardEl.appendChild(makeLabelCell(String(r + 1)));
      for(let c=0;c<state.size;c++){
        boardEl.appendChild(buildCell(state.board[r][c], dmgMarks, removalEchoes, key(r,c)));
      }
    }
  }

  function hpPipsHtml(hp, color){
    const total = hp[color] || 0;
    let html = '';
    for(let i=0;i<6;i++){
      html += `<div class="hp-pip ${i < total ? 'filled ' + (color==='B'?'black':'white') : ''}"></div>`;
    }
    return html;
  }

  function render(){
    if(history.length === 0){
      boardEl.innerHTML = '';
      statusEl.textContent = 'まだ着手がありません。';
      moveIndicator.textContent = '0 / 0';
      moveListEl.innerHTML = '';
      return;
    }
    const state = history[index];
    renderBoard(state);
    document.getElementById('hpBarB').innerHTML = hpPipsHtml(state.hp, 'B');
    document.getElementById('hpBarW').innerHTML = hpPipsHtml(state.hp, 'W');
    document.getElementById('hpCardB').classList.toggle('active', state.currentPlayer === 'B' && !state.gameOver);
    document.getElementById('hpCardW').classList.toggle('active', state.currentPlayer === 'W' && !state.gameOver);

    const desc = (state.log && state.log[0]) || '';
    let s = `<b>${index + 1}手目</b>：${desc}`;
    if(state.gameOver){
      s += state.winner === 'draw' ? '　<b>（引き分け）</b>' : `　<b>（${colorName(state.winner)}の勝利）</b>`;
    } else if(index === history.length - 1){
      s += `　（次：${colorName(state.currentPlayer)}の手番）`;
    }
    statusEl.innerHTML = s;
    moveIndicator.textContent = `${index + 1} / ${history.length}`;

    moveListEl.querySelectorAll('.entry').forEach((el) => {
      el.classList.toggle('current', Number(el.dataset.index) === index);
    });
    const currentEntry = moveListEl.querySelector(`.entry[data-index="${index}"]`);
    if(currentEntry) currentEntry.scrollIntoView({ block: 'nearest' });
  }

  function buildMoveList(){
    moveListEl.innerHTML = history.map((state, i) => {
      const desc = (state.log && state.log[0]) || '';
      return `<div class="entry" data-index="${i}">${i + 1}手目：${desc}</div>`;
    }).join('');
    moveListEl.querySelectorAll('.entry').forEach((el) => {
      el.addEventListener('click', () => {
        index = Number(el.dataset.index);
        render();
      });
    });
  }

  function goTo(newIndex){
    if(history.length === 0) return;
    index = Math.max(0, Math.min(history.length - 1, newIndex));
    render();
  }

  document.getElementById('firstBtn').addEventListener('click', () => goTo(0));
  document.getElementById('prevBtn').addEventListener('click', () => goTo(index - 1));
  document.getElementById('nextBtn').addEventListener('click', () => goTo(index + 1));
  document.getElementById('lastBtn').addEventListener('click', () => goTo(history.length - 1));
  document.getElementById('refreshBtn').addEventListener('click', () => load(true));

  document.addEventListener('keydown', (e) => {
    if(e.key === 'ArrowLeft') goTo(index - 1);
    if(e.key === 'ArrowRight') goTo(index + 1);
  });

  async function load(keepPositionIfPossible){
    try{
      const res = await fetch(`/api/games/${gameId}/history`);
      if(!res.ok){
        showError('対局が見つかりませんでした。IDをご確認のうえ、<a href="index.html">ロビー</a>からやり直してください。');
        statusEl.textContent = '';
        return;
      }
      const wasAtLatest = history.length === 0 || index === history.length - 1;
      history = await res.json();
      buildMoveList();
      if(!keepPositionIfPossible || wasAtLatest){
        index = history.length > 0 ? history.length - 1 : 0;
      } else {
        index = Math.min(index, history.length - 1);
      }
      render();
    }catch(e){
      showError('棋譜の取得に失敗しました。通信状態をご確認のうえ、もう一度お試しください。');
    }
  }

  load(false);
})();
