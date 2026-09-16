package com.dsmod.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.dsmod.probe.localapi.ApiContract;
import com.dsmod.probe.localapi.KeepAliveService;
import com.dsmod.probe.localapi.LocalApi;
import com.dsmod.probe.localapi.LocalApiConfig;
import com.dsmod.probe.localapi.LocalApiStats;

/**
 * Local API settings page, opened from 工程 / Engineering.
 *
 * <p>Everything here writes through {@link LocalApiConfig} and then reconciles
 * the listener. Changing something that is bound at socket creation time
 * (port, HTTPS, protocol) restarts the listener in place, so the user never has
 * to leave DeepSeek and come back for a setting to take effect.
 */
final class LocalApiUi {
    private LocalApiUi() {}

    static void show(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        LocalApi.initialize(activity);

        final boolean dark = DeekseepUi.isDark(activity);
        final int background = dark ? 0xFF1B1B1D : 0xFFF5F6F8;
        final int barColor = dark ? 0xFF232326 : 0xFFFFFFFF;
        final int cardColor = dark ? 0xFF2A2A2D : 0xFFFFFFFF;
        final int textColor = dark ? 0xFFF0F0F0 : 0xFF1A1A1A;
        final int subColor = dark ? 0xFFAAAAAF : 0xFF777B82;
        final int dividerColor = dark ? 0xFF3A3A3D : 0xFFEEEEEE;

        final Dialog dialog = new Dialog(
                activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(activity, 8), statusBarHeight(activity), dp(activity, 16), 0);
        bar.setBackgroundColor(barColor);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 56) + statusBarHeight(activity)));
        TextView back = text(activity, "\u2039", 28, textColor, false);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(activity, 8), 0, dp(activity, 8), 0);
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View ignored) { close(dialog, root); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 40)));
        TextView title = text(activity,
                UiLanguage.text(activity, "本地 API", "Local API"), 18, textColor, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(activity, 8);
        bar.addView(title, titleParams);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 16), dp(activity, 16),
                dp(activity, 16), dp(activity, 28));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final Runnable[] refresh = new Runnable[1];

        // ── 服务 ────────────────────────────────────────────────────
        final LinearLayout service = card(activity, cardColor);
        content.addView(service);

        final Switch master = switchView(activity, dark);
        master.setChecked(LocalApi.isRunning());
        service.addView(switchRow(activity,
                UiLanguage.text(activity, "启用本地 API", "Enable Local API"),
                UiLanguage.text(activity,
                        "在手机上提供 OpenAI / Anthropic 兼容接口，默认只监听回环地址",
                        "Serve OpenAI / Anthropic compatible endpoints, bound to loopback "
                                + "unless you widen it"),
                textColor, subColor, master));

        final TextView statusDetail = text(activity, "", 12, subColor, false);
        statusDetail.setPadding(dp(activity, 16), dp(activity, 4),
                dp(activity, 16), dp(activity, 14));
        service.addView(statusDetail);

        // ── 连接 ────────────────────────────────────────────────────
        content.addView(section(activity,
                UiLanguage.text(activity, "连接", "Connection"), subColor));
        final LinearLayout connection = card(activity, cardColor);
        content.addView(connection);

        final TextView[] protocolDetail = new TextView[1];
        connection.addView(actionRow(activity,
                UiLanguage.text(activity, "协议模式", "Protocol"),
                ApiContract.PROTOCOL_ANTHROPIC.equals(LocalApiConfig.get().protocolMode)
                        ? "Anthropic" : "OpenAI",
                textColor, subColor, new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        showProtocolPicker(activity, protocolDetail, refresh[0]);
                    }
                }, protocolDetail));

        connection.addView(divider(activity, dividerColor));
        final TextView[] portDetail = new TextView[1];
        connection.addView(actionRow(activity,
                UiLanguage.text(activity, "端口", "Port"),
                String.valueOf(LocalApiConfig.get().port),
                textColor, subColor, new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        showPortPicker(activity, portDetail, refresh[0]);
                    }
                }, portDetail));

        connection.addView(divider(activity, dividerColor));
        final TextView[] keyDetail = new TextView[1];
        connection.addView(actionRow(activity,
                UiLanguage.text(activity, "API 密钥", "API key"),
                mask(LocalApiConfig.get().apiKey),
                textColor, subColor, new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        showKeyDialog(activity, keyDetail, refresh[0]);
                    }
                }, keyDetail));

        connection.addView(divider(activity, dividerColor));
        final Switch https = switchView(activity, dark);
        https.setChecked(LocalApiConfig.get().https);
        connection.addView(switchRow(activity,
                UiLanguage.text(activity, "启用 HTTPS", "Enable HTTPS"),
                UiLanguage.text(activity,
                        "使用每设备自签证书；客户端需信任该证书或忽略校验",
                        "Uses a per device self signed certificate; clients must trust it"),
                textColor, subColor, https));

        connection.addView(divider(activity, dividerColor));
        final Switch lan = switchView(activity, dark);
        lan.setChecked(LocalApiConfig.get().allowLan);
        connection.addView(switchRow(activity,
                UiLanguage.text(activity, "允许局域网访问", "Allow LAN access"),
                UiLanguage.text(activity,
                        "关闭时只监听 127.0.0.1，仅本机可用；开启后同一网络内的任何设备"
                                + "只要拿到密钥就能使用你的账号额度",
                        "Off means 127.0.0.1 only. On lets any device on the same network "
                                + "spend your account quota with the key"),
                textColor, subColor, lan));

        // ── 行为 ────────────────────────────────────────────────────
        content.addView(section(activity,
                UiLanguage.text(activity, "行为", "Behaviour"), subColor));
        final LinearLayout behaviour = card(activity, cardColor);
        content.addView(behaviour);

        final Switch keepAlive = switchView(activity, dark);
        keepAlive.setChecked(LocalApiConfig.get().keepAliveNotification);
        behaviour.addView(switchRow(activity,
                UiLanguage.text(activity, "后台保活", "Keep alive"),
                UiLanguage.text(activity,
                        "显示常驻通知，降低后台被系统回收的概率",
                        "Shows a sticky notification so the listener survives in background"),
                textColor, subColor, keepAlive));

        behaviour.addView(divider(activity, dividerColor));
        final Switch serial = switchView(activity, dark);
        serial.setChecked(LocalApiConfig.get().serialRequests);
        behaviour.addView(switchRow(activity,
                UiLanguage.text(activity, "串行请求", "Serial requests"),
                UiLanguage.text(activity,
                        "一次只处理一个补全，避免并发打爆上游",
                        "Handle one completion at a time instead of racing upstream"),
                textColor, subColor, serial));

        behaviour.addView(divider(activity, dividerColor));
        final Switch reasoning = switchView(activity, dark);
        reasoning.setChecked(LocalApiConfig.get().forceReasoning);
        behaviour.addView(switchRow(activity,
                UiLanguage.text(activity, "强制思考模式", "Force reasoning"),
                UiLanguage.text(activity,
                        "始终按推理模型处理请求",
                        "Always route requests through the reasoning path"),
                textColor, subColor, reasoning));

        behaviour.addView(divider(activity, dividerColor));
        final Switch longContext = switchView(activity, dark);
        longContext.setChecked(LocalApiConfig.get().longContextRelay);
        behaviour.addView(switchRow(activity,
                UiLanguage.text(activity, "长上下文中继", "Long context relay"),
                UiLanguage.text(activity,
                        "超长输入改为分段中继，牺牲速度换取成功率",
                        "Relay very long inputs in segments; slower but more reliable"),
                textColor, subColor, longContext));

        behaviour.addView(divider(activity, dividerColor));
        final Switch antiCensor = switchView(activity, dark);
        antiCensor.setChecked(LocalApiConfig.get().antiCensor);
        behaviour.addView(switchRow(activity,
                UiLanguage.text(activity, "防审查", "Anti censor"),
                UiLanguage.text(activity,
                        "对返回内容做一次去敏处理",
                        "Post process responses to soften refusals"),
                textColor, subColor, antiCensor));

        behaviour.addView(divider(activity, dividerColor));
        final Switch inject = switchView(activity, dark);
        inject.setChecked(LocalApiConfig.get().injectSystemPrompt);
        behaviour.addView(switchRow(activity,
                UiLanguage.text(activity, "注入系统提示词", "Inject system prompt"),
                UiLanguage.text(activity,
                        "为每个补全请求附加一段固定系统提示",
                        "Prepend a fixed system prompt to every completion"),
                textColor, subColor, inject));

        behaviour.addView(divider(activity, dividerColor));
        final TextView[] promptDetail = new TextView[1];
        behaviour.addView(actionRow(activity,
                UiLanguage.text(activity, "系统提示词", "System prompt"),
                LocalApiConfig.get().systemPrompt,
                textColor, subColor, new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        showPromptDialog(activity, promptDetail);
                    }
                }, promptDetail));

        // ── 诊断 ────────────────────────────────────────────────────
        content.addView(section(activity,
                UiLanguage.text(activity, "诊断", "Diagnostics"), subColor));
        final LinearLayout tools = card(activity, cardColor);
        content.addView(tools);

        final TextView[] statsDetail = new TextView[1];
        tools.addView(actionRow(activity,
                UiLanguage.text(activity, "请求统计", "Request statistics"),
                "", textColor, subColor, new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        showStatsDialog(activity, statsDetail);
                    }
                }, statsDetail));

        tools.addView(divider(activity, dividerColor));
        tools.addView(actionRow(activity,
                UiLanguage.text(activity, "复制连接信息", "Copy connection details"),
                UiLanguage.text(activity,
                        "把 Base URL 与密钥复制到剪贴板",
                        "Copy the base URL and key to the clipboard"),
                textColor, subColor, new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        copy(activity, LocalApi.connectionCard(activity));
                    }
                }, null));

        tools.addView(divider(activity, dividerColor));
        tools.addView(actionRow(activity,
                UiLanguage.text(activity, "查看 API 日志", "View API log"),
                UiLanguage.text(activity,
                        "最近数百条请求与错误记录",
                        "The most recent request and error records"),
                textColor, subColor, new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        showLogDialog(activity);
                    }
                }, null));

        // ── 接线 ────────────────────────────────────────────────────
        refresh[0] = new Runnable() {
            @Override public void run() {
                LocalApiConfig.State state = LocalApiConfig.get();
                statusDetail.setText(endpointSummary(activity));
                protocolDetail[0].setText(
                        ApiContract.PROTOCOL_ANTHROPIC.equals(state.protocolMode)
                                ? "Anthropic" : "OpenAI");
                portDetail[0].setText(String.valueOf(state.port));
                keyDetail[0].setText(mask(state.apiKey));
                statsDetail[0].setText(LocalApiStats.summary());
            }
        };

        master.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            private boolean reverting;

            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (reverting) return;
                if (checked) {
                    try {
                        if (LocalApi.start(activity)) {
                            refresh[0].run();
                            return;
                        }
                    } catch (Throwable failure) {
                        toast(activity, UiLanguage.text(activity,
                                "启动失败：" + failure,
                                "Could not start: " + failure));
                    }
                } else {
                    LocalApi.stop();
                    try {
                        activity.stopService(KeepAliveService.createIntent(activity));
                    } catch (Throwable ignored) {
                        // The service may never have been started.
                    }
                    refresh[0].run();
                    return;
                }
                reverting = true;
                button.setChecked(!checked);
                reverting = false;
            }
        });

        https.setOnCheckedChangeListener(restarting(activity, refresh[0],
                new Toggler() {
                    @Override public void apply(boolean value) {
                        LocalApiConfig.setHttps(value);
                    }
                }, https));

        lan.setOnCheckedChangeListener(restarting(activity, refresh[0],
                new Toggler() {
                    @Override public void apply(boolean value) {
                        LocalApiConfig.setAllowLan(value);
                    }
                }, lan));

        keepAlive.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                applyFlag("keepAliveNotification", checked);
                // Reconciling through the same path the host resume hook uses keeps the
                // service and the flag from drifting apart.
                LocalApi.onHostResumed(activity);
            }
        });

        serial.setOnCheckedChangeListener(simple("serialRequests", serial));
        reasoning.setOnCheckedChangeListener(simple("forceReasoning", reasoning));
        longContext.setOnCheckedChangeListener(simple("longContextRelay", longContext));
        antiCensor.setOnCheckedChangeListener(simple("antiCensor", antiCensor));
        inject.setOnCheckedChangeListener(simple("injectSystemPrompt", inject));

        refresh[0].run();

        UiLanguage.localizeTree(activity, root);
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(background));
        }
        DeekseepUi.trackChildDialog(dialog);
        DeekseepUi.openWithSlide(dialog, root);
        dialog.setOnKeyListener(new Dialog.OnKeyListener() {
            public boolean onKey(DialogInterface d, int code, KeyEvent event) {
                if (code == KeyEvent.KEYCODE_BACK
                        && event.getAction() == KeyEvent.ACTION_UP) {
                    close(dialog, root);
                    return true;
                }
                return false;
            }
        });
    }

    // ------------------------------------------------------------ interactions

    private interface Toggler {
        void apply(boolean value);
    }

    private static CompoundButton.OnCheckedChangeListener simple(final String key,
            final CompoundButton button) {
        return new CompoundButton.OnCheckedChangeListener() {
            private boolean reverting;

            @Override public void onCheckedChanged(CompoundButton view, boolean checked) {
                if (reverting) return;
                applyFlag(key, checked);
                if (flag(key) != checked) {
                    reverting = true;
                    button.setChecked(!checked);
                    reverting = false;
                }
            }
        };
    }

    /** Applies a setting that requires rebinding, then restarts the listener. */
    private static CompoundButton.OnCheckedChangeListener restarting(
            final Activity activity, final Runnable refresh, final Toggler toggler,
            final CompoundButton button) {
        return new CompoundButton.OnCheckedChangeListener() {
            private boolean reverting;

            @Override public void onCheckedChanged(CompoundButton view, boolean checked) {
                if (reverting) return;
                boolean wasRunning = LocalApi.isRunning();
                toggler.apply(checked);
                if (!wasRunning || restart(activity)) {
                    refresh.run();
                    return;
                }
                toggler.apply(!checked);
                reverting = true;
                button.setChecked(!checked);
                reverting = false;
            }
        };
    }

    private static void applyFlag(String key, boolean value) {
        if ("enabled".equals(key)) LocalApiConfig.setEnabled(value);
        else if ("https".equals(key)) LocalApiConfig.setHttps(value);
        else if ("allowLan".equals(key)) LocalApiConfig.setAllowLan(value);
        else if ("serialRequests".equals(key)) LocalApiConfig.setSerialRequests(value);
        else if ("antiCensor".equals(key)) LocalApiConfig.setAntiCensor(value);
        else if ("injectSystemPrompt".equals(key)) {
            LocalApiConfig.setInjectSystemPrompt(value);
        } else if ("longContextRelay".equals(key)) {
            LocalApiConfig.setLongContextRelay(value);
        } else if ("forceReasoning".equals(key)) LocalApiConfig.setForceReasoning(value);
        else if ("keepAliveNotification".equals(key)) {
            LocalApiConfig.setKeepAliveNotification(value);
        }
    }

    private static boolean flag(String key) {
        LocalApiConfig.State state = LocalApiConfig.get();
        if ("enabled".equals(key)) return state.enabled;
        if ("https".equals(key)) return state.https;
        if ("allowLan".equals(key)) return state.allowLan;
        if ("serialRequests".equals(key)) return state.serialRequests;
        if ("antiCensor".equals(key)) return state.antiCensor;
        if ("injectSystemPrompt".equals(key)) return state.injectSystemPrompt;
        if ("longContextRelay".equals(key)) return state.longContextRelay;
        if ("forceReasoning".equals(key)) return state.forceReasoning;
        if ("keepAliveNotification".equals(key)) return state.keepAliveNotification;
        return false;
    }

    private static boolean restart(Activity activity) {
        LocalApi.stop();
        try {
            return LocalApi.start(activity);
        } catch (Throwable failure) {
            toast(activity, UiLanguage.text(activity,
                    "重启监听失败：" + failure, "Could not restart: " + failure));
            return false;
        }
    }

    private static void showProtocolPicker(final Activity activity,
            final TextView[] detail, final Runnable refresh) {
        final String[] labels = new String[]{"OpenAI (/v1)", "Anthropic (/v1)"};
        new AlertDialog.Builder(activity)
                .setTitle(UiLanguage.text(activity, "协议模式", "Protocol"))
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        boolean wasRunning = LocalApi.isRunning();
                        LocalApiConfig.setProtocolMode(which == 1
                                ? ApiContract.PROTOCOL_ANTHROPIC
                                : ApiContract.PROTOCOL_OPENAI);
                        if (wasRunning) restart(activity);
                        detail[0].setText(labels[which]);
                        refresh.run();
                    }
                })
                .setNegativeButton(UiLanguage.text(activity, "取消", "Cancel"), null)
                .show();
    }

    private static void showPortPicker(final Activity activity,
            final TextView[] detail, final Runnable refresh) {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(LocalApiConfig.get().port));
        input.setSingleLine(true);
        new AlertDialog.Builder(activity)
                .setTitle(UiLanguage.text(activity, "端口", "Port"))
                .setMessage(UiLanguage.text(activity,
                        "取值范围 1024 - 65535", "Anywhere from 1024 to 65535"))
                .setView(input)
                .setPositiveButton(UiLanguage.text(activity, "保存", "Save"),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                int port = parsePort(input.getText());
                                if (port == 0) {
                                    toast(activity, UiLanguage.text(activity,
                                            "端口无效", "Invalid port"));
                                    return;
                                }
                                boolean wasRunning = LocalApi.isRunning();
                                LocalApiConfig.setPort(port);
                                if (wasRunning && !restart(activity)) {
                                    LocalApiConfig.setPort(LocalApiConfig.DEFAULT_PORT);
                                }
                                detail[0].setText(String.valueOf(LocalApiConfig.get().port));
                                refresh.run();
                            }
                        })
                .setNegativeButton(UiLanguage.text(activity, "取消", "Cancel"), null)
                .show();
    }

    private static int parsePort(CharSequence raw) {
        if (raw == null) return 0;
        try {
            int value = Integer.parseInt(raw.toString().trim());
            if (value < LocalApiConfig.MIN_PORT || value > LocalApiConfig.MAX_PORT) return 0;
            return value;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void showKeyDialog(final Activity activity,
            final TextView[] detail, final Runnable refresh) {
        final String current = LocalApiConfig.get().apiKey;
        final String[] labels = new String[]{
                UiLanguage.text(activity, "复制密钥", "Copy key"),
                UiLanguage.text(activity, "轮换密钥", "Rotate key"),
                UiLanguage.text(activity, "自定义密钥", "Set custom key")};
        new AlertDialog.Builder(activity)
                .setTitle(current)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            copy(activity, LocalApiConfig.get().apiKey);
                        } else if (which == 1) {
                            LocalApiConfig.rotateKey();
                            detail[0].setText(mask(LocalApiConfig.get().apiKey));
                            refresh.run();
                        } else {
                            showCustomKeyDialog(activity, detail, refresh);
                        }
                    }
                })
                .setNegativeButton(UiLanguage.text(activity, "关闭", "Close"), null)
                .show();
    }

    private static void showCustomKeyDialog(final Activity activity,
            final TextView[] detail, final Runnable refresh) {
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(LocalApiConfig.get().apiKey);
        new AlertDialog.Builder(activity)
                .setTitle(UiLanguage.text(activity, "自定义密钥", "Set custom key"))
                .setMessage(UiLanguage.text(activity,
                        "8-256 位可打印字符，不含空格",
                        "8 to 256 printable characters, no whitespace"))
                .setView(input)
                .setPositiveButton(UiLanguage.text(activity, "保存", "Save"),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                String error = LocalApiConfig.setCustomKey(
                                        input.getText() == null ? "" : input.getText().toString());
                                if (error != null) {
                                    toast(activity, UiLanguage.text(activity,
                                            "密钥无效：" + error, "Invalid key: " + error));
                                    return;
                                }
                                detail[0].setText(mask(LocalApiConfig.get().apiKey));
                                refresh.run();
                            }
                        })
                .setNegativeButton(UiLanguage.text(activity, "取消", "Cancel"), null)
                .show();
    }

    private static void showPromptDialog(final Activity activity, final TextView[] detail) {
        final EditText input = new EditText(activity);
        input.setSingleLine(false);
        input.setMinLines(5);
        input.setGravity(Gravity.TOP);
        input.setText(LocalApiConfig.get().systemPrompt);
        new AlertDialog.Builder(activity)
                .setTitle(UiLanguage.text(activity, "系统提示词", "System prompt"))
                .setView(input)
                .setPositiveButton(UiLanguage.text(activity, "保存", "Save"),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                String value = input.getText() == null
                                        ? "" : input.getText().toString();
                                LocalApiConfig.setSystemPrompt(value);
                                detail[0].setText(LocalApiConfig.get().systemPrompt);
                            }
                        })
                .setNegativeButton(UiLanguage.text(activity, "取消", "Cancel"), null)
                .show();
    }

    private static void showStatsDialog(final Activity activity, final TextView[] detail) {
        new AlertDialog.Builder(activity)
                .setTitle(UiLanguage.text(activity, "请求统计", "Request statistics"))
                .setMessage(LocalApiStats.summary())
                .setPositiveButton(UiLanguage.text(activity, "关闭", "Close"), null)
                .setNeutralButton(UiLanguage.text(activity, "清零", "Reset"),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                LocalApiStats.reset();
                                detail[0].setText(LocalApiStats.summary());
                            }
                        })
                .show();
    }

    private static void showLogDialog(final Activity activity) {
        String log = LocalApiStats.recentLog();
        if (log == null || log.length() == 0) {
            log = UiLanguage.text(activity, "暂无记录", "No records yet");
        }
        new AlertDialog.Builder(activity)
                .setTitle(UiLanguage.text(activity, "API 日志", "API log"))
                .setMessage(log)
                .setPositiveButton(UiLanguage.text(activity, "关闭", "Close"), null)
                .setNeutralButton(UiLanguage.text(activity, "复制", "Copy"),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                copy(activity, LocalApiStats.recentLog());
                            }
                        })
                .show();
    }

    // ------------------------------------------------------------------ helpers

    private static String endpointSummary(Activity activity) {
        StringBuilder builder = new StringBuilder();
        if (!LocalApi.isRunning()) {
            builder.append(UiLanguage.text(activity, "未运行", "Stopped"));
            return builder.toString();
        }
        String openAi = LocalApi.openAiBaseUrl();
        String anthropic = LocalApi.anthropicBaseUrl();
        boolean anthropicMode = ApiContract.PROTOCOL_ANTHROPIC.equals(
                LocalApiConfig.get().protocolMode);
        builder.append(UiLanguage.text(activity, "运行中", "Running"));
        builder.append(LocalApiConfig.get().allowLan
                ? UiLanguage.text(activity, " · 局域网可访问", " · reachable on LAN")
                : UiLanguage.text(activity, " · 仅本机", " · this device only"));
        builder.append('\n');
        if (anthropicMode) {
            if (anthropic != null) builder.append(anthropic);
        } else if (openAi != null) {
            builder.append(openAi);
        }
        return builder.toString();
    }

    private static String mask(String key) {
        if (key == null) return "";
        if (key.length() <= 10) return key;
        return key.substring(0, 6) + "…" + key.substring(key.length() - 4);
    }

    private static void copy(Context context, String value) {
        try {
            ClipboardManager manager = (ClipboardManager) context.getSystemService(
                    Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("deekseep", value));
                toast(context, UiLanguage.text(context, "已复制", "Copied"));
                return;
            }
        } catch (Throwable ignored) {
            // Fall through to showing the value so nothing is lost.
        }
        toast(context, value);
    }

    private static void toast(Context context, String value) {
        try {
            Toast.makeText(context, value, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // Toasts from a non UI context are not fatal.
        }
    }

    // ------------------------------------------------------------------- views

    private static View section(Context context, String label, int color) {
        TextView view = text(context, label, 13, color, true);
        view.setPadding(dp(context, 6), dp(context, 14), dp(context, 6), dp(context, 8));
        return view;
    }

    private static View actionRow(Context context, String title, String detail,
            int textColor, int subColor, View.OnClickListener listener,
            TextView[] detailHolder) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(context, 16), dp(context, 13), dp(context, 16), dp(context, 13));
        row.addView(text(context, title, 15, textColor, false));
        if (detail == null) detail = "";
        String shown = detail.length() > 60 ? detail.substring(0, 57) + "…" : detail;
        TextView value = text(context, shown, 12, subColor, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 3);
        row.addView(value, params);
        if (detailHolder != null) detailHolder[0] = value;
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(listener);
        return row;
    }

    private static View switchRow(Context context, String title, String detail,
            int textColor, int subColor, Switch control) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 16), dp(context, 13), dp(context, 14), dp(context, 13));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(context, title, 15, textColor, false));
        if (detail != null && detail.length() > 0) {
            TextView sub = text(context, detail, 12, subColor, false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(context, 3);
            labels.addView(sub, params);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(control, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static LinearLayout card(Context context, int color) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, 13));
        card.setBackground(drawable);
        if (Build.VERSION.SDK_INT >= 21) card.setElevation(dp(context, 1));
        return card;
    }

    private static View divider(Context context, int color) {
        View divider = new View(context);
        divider.setBackgroundColor(color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1));
        params.leftMargin = dp(context, 16);
        divider.setLayoutParams(params);
        return divider;
    }

    private static Switch switchView(Context context, boolean dark) {
        Switch value = new HubInsetSwitch(context);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        value.setThumbTintList(new android.content.res.ColorStateList(
                states, new int[]{DeekseepUi.BRAND, dark ? 0xFFCCCCCC : 0xFFFFFFFF}));
        value.setTrackTintList(new android.content.res.ColorStateList(
                states, new int[]{0xFFADBFFF, dark ? 0xFF555555 : 0xFFBFBFBF}));
        value.setBackground(null);
        return value;
    }

    private static TextView text(Context context, String value, float sp, int color,
            boolean bold) {
        TextView view = new TextView(context);
        view.setText(value == null ? "" : value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private static int statusBarHeight(Context context) {
        int resource = context.getResources().getIdentifier(
                "status_bar_height", "dimen", "android");
        return resource > 0
                ? context.getResources().getDimensionPixelSize(resource) : 0;
    }

    private static int dp(Context context, float value) {
        return DeekseepUi.dp(context, value);
    }

    private static void close(final Dialog dialog, View root) {
        DeekseepUi.slideOutAndDismiss(dialog, root);
    }
}
