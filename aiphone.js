/* AI 연결용 등록 번호 자동 입력 — 사용자가 따로 입력하지 않는다. 모든 화면이 읽기 전에 먼저 실행(동기 로드). */
try { if (localStorage.getItem('SIMAS_AI_PHONE') !== '01027042545') localStorage.setItem('SIMAS_AI_PHONE', '01027042545'); } catch (e) {}
