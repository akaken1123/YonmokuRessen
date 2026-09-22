(function(){
  const errorBox = document.getElementById('errorBox');
  const ratingsBody = document.getElementById('ratingsBody');
  const emptyNote = document.getElementById('emptyNote');

  function showError(msg){
    errorBox.innerHTML = `<div class="error-box">${msg}</div>`;
  }

  async function load(){
    try{
      const res = await fetch('/api/ratings');
      if(!res.ok) throw new Error('レーティングの取得に失敗しました');
      const entries = await res.json();
      ratingsBody.innerHTML = entries.map((e, i) => `
        <tr>
          <td>${i + 1}</td>
          <td>${escapeHtml(e.nickname)}</td>
          <td>${Math.round(e.rating)}</td>
          <td>${e.wins}</td>
          <td>${e.losses}</td>
          <td>${e.draws}</td>
        </tr>
      `).join('');
      emptyNote.style.display = entries.length === 0 ? '' : 'none';
    }catch(e){
      showError(e.message || '通信エラーが発生しました。');
    }
  }

  function escapeHtml(s){
    return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  }

  document.getElementById('refreshBtn').addEventListener('click', load);
  load();
})();
