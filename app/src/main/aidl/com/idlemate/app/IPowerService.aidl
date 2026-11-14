package com.idlemate.app;

// 由 Shizuku 以 shell 身分執行的服務介面
interface IPowerService {
    // Shizuku 規定的結束方法（固定編號）
    void destroy() = 16777114;

    // 開 / 關實體螢幕面板（不讓系統進入睡眠）。回傳執行結果說明。
    String setDisplayPower(boolean on) = 1;

    // 送出「喚醒」按鍵，讓睡眠中的手機亮屏（進入鎖屏畫面）
    String wakeUp() = 2;

    // 只顯示鎖屏（讓手機進入鎖定狀態），但不讓系統睡眠
    String lockKeyguard() = 3;

    // 在 shell 行程用 getevent 監聽觸控；touchStamp 回傳最近一次「手指按下」的時間（0 = 尚無）
    String startTouchWatch() = 4;
    long touchStamp() = 5;
    void stopTouchWatch() = 6;
    String touchInfo() = 7;
}
