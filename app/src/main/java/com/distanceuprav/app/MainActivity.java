package com.distanceuprav.app;

import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.net.*;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText mac, host, port;
    private TextView result;
    private final int bg = Color.rgb(14,18,31), panel = Color.rgb(28,35,53), accent = Color.rgb(109,146,255);
    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private TextView text(String value, int size, int color) { TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t; }
    private void add(LinearLayout root, View v, int top) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(top); root.addView(v,p); }
    private EditText field(LinearLayout root,String hint,String saved, int top) {
        EditText e=new EditText(this); e.setSingleLine(true); e.setText(saved); e.setHint(hint); e.setTextColor(Color.WHITE); e.setHintTextColor(0xFF8590A9);
        e.setTextSize(15); e.setPadding(dp(14),dp(12),dp(14),dp(12)); e.setBackground(shape(panel,14)); add(root,e,top); return e;
    }
    private Button button(String label, int color) { Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(16); b.setBackground(shape(color,14)); return b; }
    private void update(String text) { main.post(() -> result.setText(text)); }
    private String pref(String key,String fallback) {return getPreferences(MODE_PRIVATE).getString(key,fallback);}
    private void save() {getPreferences(MODE_PRIVATE).edit().putString("mac",mac.getText().toString().trim()).putString("host",host.getText().toString().trim()).putString("port",port.getText().toString().trim()).apply();}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().setStatusBarColor(bg); getWindow().setNavigationBarColor(bg);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(bg);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(24),dp(42),dp(24),dp(28)); scroll.addView(root);
        TextView title=text("⚡ DistanceUprav",28,Color.WHITE); title.setTypeface(null,Typeface.BOLD); add(root,title,0);
        TextView subtitle=text("Управление домашним компьютером",14,0xFF9BA8C5); add(root,subtitle,8);
        TextView name=text("🖥  Мой компьютер",21,Color.WHITE); name.setTypeface(null,Typeface.BOLD); add(root,name,35);
        TextView hint=text("Настрой подключение и проверь доступность ПК.",14,0xFF9BA8C5); add(root,hint,12);
        add(root,text("MAC-адрес Ethernet",14,0xFFD8E1F5),30);
        mac=field(root,"AA:BB:CC:DD:EE:FF",pref("mac",""),8);
        add(root,text("IP-адрес компьютера",14,0xFFD8E1F5),20);
        host=field(root,"192.168.1.100",pref("host",""),8);
        add(root,text("TCP-порт (дополнительная диагностика)",14,0xFFD8E1F5),20);
        port=field(root,"3389",pref("port","3389"),8); port.setInputType(2);
        TextView note=text("Сначала приложение отправляет ping, затем проверяет TCP-порт. Закрытый порт 3389 — это нормально, если удалённый рабочий стол Windows выключен. Отсутствие ответа не доказывает, что ПК выключен.",12,0xFF9BA8C5); add(root,note,14);
        Button power=button("⏻  Включить компьютер",accent); add(root,power,30);
        Button check=button("◎  Проверить доступность",panel); add(root,check,10);
        result=text("Нажми кнопку: проверим ping и TCP-порт компьютера.",14,0xFFD8E1F5);
        result.setMinHeight(dp(56));
        result.setPadding(dp(14),dp(14),dp(14),dp(14));
        result.setBackground(shape(panel,14));
        add(root,result,12);
        power.setOnClickListener(v -> {save(); String m=mac.getText().toString().trim(); worker.execute(() -> {
            try { byte[] packet=packet(m); send(packet); update("✓ Magic Packet отправлен трижды. Ожидай запуска компьютера."); }
            catch(Exception e) {update("Ошибка Wake-on-LAN: "+e.getMessage());}
        });});
        check.setOnClickListener(v -> {
            save();
            String h=host.getText().toString().trim();
            String p=port.getText().toString().trim();
            if(h.isEmpty()) {
                result.setText("⚠ Укажи IP-адрес ПК. В Windows выполни ipconfig и найди IPv4.");
                return;
            }
            final int pn;
            try {
                pn=Integer.parseInt(p);
                if(pn<1 || pn>65535) throw new NumberFormatException();
            } catch(NumberFormatException e) {
                result.setText("⚠ TCP-порт должен быть числом от 1 до 65535.");
                return;
            }
            result.setText("⏳ Проверяю ping и TCP для " + h + "…");
            check.setEnabled(false);
            worker.execute(() -> {
                boolean pingOk=ping(h);
                boolean tcpOpen=false;
                boolean tcpRefused=false;
                String tcpDetail="Порт " + pn + " не отвечает.";
                try(Socket socket=new Socket()) {
                    socket.connect(new InetSocketAddress(h,pn),1800);
                    tcpOpen=true;
                    tcpDetail="TCP-порт "+pn+" открыт.";
                } catch(ConnectException e) {
                    if(e.getMessage()!=null && e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("refused")) {
                        tcpRefused=true;
                        tcpDetail="TCP-порт "+pn+" закрыт (соединение отклонено).";
                    } else {
                        tcpDetail="Ошибка TCP-подключения: "+e.getMessage();
                    }
                } catch(UnknownHostException e) {
                    tcpDetail="Не удалось найти указанный IP-адрес или имя ПК.";
                } catch(SocketTimeoutException e) {
                    tcpDetail="TCP-порт "+pn+" не ответил за 1,8 секунды.";
                } catch(Exception e) {
                    tcpDetail="Ошибка TCP: "+e.getClass().getSimpleName()+".";
                }
                String message;
                if(pingOk) {
                    message="✅ ПК отвечает на ping — устройство доступно в сети.\n"+tcpDetail;
                } else if(tcpOpen) {
                    message="✅ ПК отвечает через TCP. Ping не получил ответа (возможно, ICMP заблокирован).\n"+tcpDetail;
                } else if(tcpRefused) {
                    message="🟡 TCP-соединение отклонено: устройство, вероятно, доступно, но порт закрыт. Ping не ответил.\n"+tcpDetail;
                } else {
                    message="⚠ Не удалось подтвердить доступность компьютера. Это НЕ означает, что он выключен. Проверь IPv4, одну Wi-Fi/LAN сеть и разрешение ICMP Echo в брандмауэре Windows.\n"+tcpDetail;
                }
                final String response=message;
                main.post(() -> {
                    result.setText(response);
                    check.setEnabled(true);
                });
            });
        });
        setContentView(scroll);
    }
    // Android's system ping sends an ICMP echo without requiring a Windows service.
    // A failed ping alone cannot establish that a PC is powered off.
    private static boolean ping(String address) {
        Process process=null;
        try {
            process=new ProcessBuilder("/system/bin/ping","-c","1","-W","2",address)
                    .redirectErrorStream(true).start();
            if(!process.waitFor(3500,TimeUnit.MILLISECONDS)) return false;
            return process.exitValue()==0;
        } catch(Exception e) {
            return false;
        } finally {
            if(process!=null) process.destroy();
        }
    }

    private static byte[] packet(String mac) {
        String clean=mac.replace(":","").replace("-","").replace(" ","");
        if(!clean.matches("[0-9a-fA-F]{12}")) throw new IllegalArgumentException("MAC должен содержать 12 шестнадцатеричных символов");
        byte[] bytes=new byte[6]; for(int i=0;i<6;i++) bytes[i]=(byte)Integer.parseInt(clean.substring(i*2,i*2+2),16);
        byte[] magic=new byte[102]; for(int i=0;i<6;i++) magic[i]=(byte)0xff;
        for(int i=6;i<102;i++) magic[i]=bytes[(i-6)%6]; return magic;
    }
    private void send(byte[] magic) throws IOException {
        WifiManager wm=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if(wm==null) throw new IOException("Wi-Fi недоступен");
        DhcpInfo dhcp=wm.getDhcpInfo();
        if(dhcp==null || dhcp.ipAddress==0 || dhcp.netmask==0) throw new IOException("Подключись к домашнему Wi-Fi");
        int broadcast=(dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
        byte[] address={(byte)broadcast,(byte)(broadcast>>>8),(byte)(broadcast>>>16),(byte)(broadcast>>>24)};
        InetAddress target=InetAddress.getByAddress(address);
        try(DatagramSocket socket=new DatagramSocket()) {
            socket.setBroadcast(true);
            DatagramPacket pkt=new DatagramPacket(magic,magic.length,target,9);
            for(int i=0;i<3;i++) socket.send(pkt);
        }
    }
    @Override protected void onDestroy() {worker.shutdown(); super.onDestroy();}
}
