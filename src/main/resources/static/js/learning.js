(function(){
  const errorBox = document.getElementById('errorBox');
  const generationLabel = document.getElementById('generationLabel');
  const lastSummaryBox = document.getElementById('lastSummaryBox');
  const weightsBody = document.getElementById('weightsBody');
  const gamesInput = document.getElementById('gamesInput');
  const trainBtn = document.getElementById('trainBtn');

  const WEIGHT_LABELS = {
    hpWeight: 'HP差1点あたりの価値',
    pendingWeight: '保留ダメージ1点あたりの見込み価値',
    boardScoreWeight: '盤面ポテンシャルの重み',
    centerWeight: '中央寄りボーナス',
    dmgMarkBonus: 'ダメージ増加マークのボーナス',
    echoMarkBonus: '除外あとマークのボーナス',
    openTwoFactor: '両端が開いたラインの倍率',
    openOneFactor: '片端だけ開いたラインの倍率',
    closedFactor: '両端が塞がったラインの倍率'
  };

  function showError(msg){
    errorBox.innerHTML = `<div class="error-box">${msg}</div>`;
  }

  function renderWeights(weights){
    weightsBody.innerHTML = Object.keys(WEIGHT_LABELS).map(key => `
      <tr><td>${WEIGHT_LABELS[key]}</td><td>${Number(weights[key]).toFixed(4)}</td></tr>
    `).join('');
  }

  function renderSummary(summary){
    if(!summary){
      lastSummaryBox.innerHTML = '<p>まだ学習を実行していません。</p>';
      return;
    }
    const resultText = summary.promoted
      ? '挑戦者の勝率が上回ったため、重みを更新しました。'
      : '挑戦者はチャンピオンを上回れなかったため、重みは変わっていません。';
    lastSummaryBox.innerHTML = `
      <p>直近の学習結果：${summary.games}局中、挑戦者${summary.challengerWins}勝・チャンピオン${summary.championWins}勝・引き分け${summary.draws}
      （挑戦者勝率${(summary.challengerScore * 100).toFixed(1)}%）。${resultText}</p>
    `;
  }

  async function loadStatus(){
    try{
      const res = await fetch('/api/learning/status');
      if(!res.ok) throw new Error('状態の取得に失敗しました');
      const status = await res.json();
      generationLabel.textContent = status.generation;
      renderSummary(status.lastSummary);
      renderWeights(status.weights);
    }catch(e){
      showError(e.message || '通信エラーが発生しました。');
    }
  }

  trainBtn.addEventListener('click', async ()=>{
    const games = Math.max(2, Math.min(200, parseInt(gamesInput.value, 10) || 20));
    trainBtn.disabled = true;
    trainBtn.textContent = '学習中…';
    try{
      const res = await fetch(`/api/learning/train?games=${games}`, { method: 'POST' });
      if(!res.ok) throw new Error('学習の実行に失敗しました');
      const summary = await res.json();
      generationLabel.textContent = summary.generation;
      renderSummary(summary);
      renderWeights(summary.currentWeights);
    }catch(e){
      showError(e.message || '通信エラーが発生しました。');
    }finally{
      trainBtn.disabled = false;
      trainBtn.textContent = '学習を実行する';
    }
  });

  loadStatus();
})();
