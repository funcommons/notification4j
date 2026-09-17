package fun.commons.notification4j.engine;

import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.properties.NfyProperties;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 第 26 步 engine 层单测：EmailSender MIME 组装与 SMTP 配置传递（mockStatic Session/Transport，零网络）。
 * 断言面：mail.smtp.* 属性表（STARTTLS + connection/timeout/write 三超时 + auth 口径）、
 * MIME 编码（UTF-8 encoded-word）、from/to/subject/content 组装（title 兼文案缺省）、
 * Transport.send 异常归失败 SendResult（不外抛，交引擎重试→DEAD）。
 */
// VECTOR: TAG=step26-unit
class EmailSenderTest {

    private final NfyProperties.Engine conf = new NfyProperties.Engine();
    private final EmailSender sender = new EmailSender(conf);

    /** mock Session：MimeMessage 构造期 initConfiguration 会读 session.getProperties()，须返回非 null */
    private Session mockSession() {
        Session session = mock(Session.class);
        when(session.getProperties()).thenReturn(new Properties());
        return session;
    }

    private static NfyaDelivery delivery(String title) {
        NfyaDelivery d = new NfyaDelivery();
        d.setTitle(title);
        return d;
    }

    private static NfyaChannel channel(String target) {
        NfyaChannel ch = new NfyaChannel();
        ch.setId(7L);
        ch.setChannelType("EMAIL");
        ch.setTarget(target);
        return ch;
    }

    @Test
    void channelType_is_email() {
        assertThat(sender.channelType()).isEqualTo("EMAIL");
        assertThat(sender.supports("EMAIL")).isTrue();
        assertThat(sender.supports("DINGTALK")).isFalse();
    }

    @Test
    void blank_mail_host_fails_fast_without_session_or_transport() {
        conf.setMailHost("  ");
        ChannelSender.SendResult r = sender.send(delivery("t"), channel("a@b.com"));
        assertThat(r.ok()).isFalse();
        assertThat(r.summary()).isEqualTo("SMTP 未配置(nfy.engine.mail-host)");
    }

    @Test
    void send_assembles_mime_and_passes_starttls_and_three_timeouts() throws Exception {
        conf.setMailHost("smtp.example.com");
        conf.setMailPort(465);
        conf.setMailUsername("ops@example.com");
        conf.setMailPassword("secret");
        conf.setMailFrom("no-reply@example.com");

        Session session = mockSession();
        ArgumentCaptor<Properties> propsCap = ArgumentCaptor.forClass(Properties.class);
        ArgumentCaptor<jakarta.mail.Message> msgCap = ArgumentCaptor.forClass(jakarta.mail.Message.class);
        try (var mockedSession = mockStatic(Session.class);
             var mockedTransport = mockStatic(Transport.class)) {
            mockedSession.when(() -> Session.getInstance(any(Properties.class), any(Authenticator.class)))
                    .thenReturn(session);

            ChannelSender.SendResult r = sender.send(delivery("磁盘使用率告警"), channel("a@b.com,c@d.com"));

            assertThat(r.ok()).isTrue();
            assertThat(r.summary()).isEqualTo("SMTP 2xx");
            mockedSession.verify(() -> Session.getInstance(propsCap.capture(), any(Authenticator.class)));
            Properties props = propsCap.getValue();
            assertThat(props.getProperty("mail.smtp.host")).isEqualTo("smtp.example.com");
            assertThat(props.getProperty("mail.smtp.port")).isEqualTo("465");
            assertThat(props.getProperty("mail.smtp.auth")).isEqualTo("true"); // 有 username → 需认证
            assertThat(props.getProperty("mail.smtp.starttls.enable")).isEqualTo("true"); // 防凭据明文过网
            assertThat(props.getProperty("mail.smtp.connectiontimeout")).isEqualTo("10000");
            assertThat(props.getProperty("mail.smtp.timeout")).isEqualTo("30000");
            assertThat(props.getProperty("mail.smtp.writetimeout")).isEqualTo("30000");

            mockedTransport.verify(() -> Transport.send(msgCap.capture()));
        }
        MimeMessage msg = (MimeMessage) msgCap.getValue();
        assertThat(msg.getSubject()).isEqualTo("磁盘使用率告警"); // 解码后原文
        assertThat(msg.getHeader("Subject")[0]).startsWith("=?UTF-8?"); // 非 ASCII 走 UTF-8 encoded-word
        assertThat(((InternetAddress) msg.getFrom()[0]).getAddress()).isEqualTo("no-reply@example.com");
        assertThat(msg.getAllRecipients()).hasSize(2); // 逗号多收件人
        assertThat(((InternetAddress) msg.getAllRecipients()[0]).getAddress()).isEqualTo("a@b.com");
        assertThat(msg.isMimeType("text/plain")).isTrue();
        assertThat(msg.getContent()).isEqualTo("磁盘使用率告警"); // content=title（实现口径）
    }

    @Test
    void blank_username_disables_smtp_auth_and_blank_from_uses_default_sender() throws Exception {
        conf.setMailHost("smtp.example.com");
        conf.setMailFrom("  ");

        Session session = mockSession();
        ArgumentCaptor<Properties> propsCap = ArgumentCaptor.forClass(Properties.class);
        ArgumentCaptor<Message> msgCap = ArgumentCaptor.forClass(Message.class);
        try (var mockedSession = mockStatic(Session.class);
             var mockedTransport = mockStatic(Transport.class)) {
            mockedSession.when(() -> Session.getInstance(any(Properties.class), any(Authenticator.class)))
                    .thenReturn(session);
            sender.send(delivery("hello"), channel("a@b.com"));
            mockedSession.verify(() -> Session.getInstance(propsCap.capture(), any(Authenticator.class)));
            assertThat(propsCap.getValue().getProperty("mail.smtp.auth")).isEqualTo("false"); // 匿名发送
            mockedTransport.verify(() -> Transport.send(msgCap.capture()));
        }
        assertThat(((InternetAddress) msgCap.getValue().getFrom()[0]).getAddress())
                .isEqualTo("notification4j@localhost"); // from 缺省
    }

    @Test
    void blank_title_falls_back_to_default_subject_and_text() throws Exception {
        conf.setMailHost("smtp.example.com");
        Session session = mockSession(); // 先建好（嵌套 stub 会触发 UnfinishedStubbing）
        ArgumentCaptor<Message> msgCap = ArgumentCaptor.forClass(Message.class);
        try (var mockedSession = mockStatic(Session.class);
             var mockedTransport = mockStatic(Transport.class)) {
            mockedSession.when(() -> Session.getInstance(any(Properties.class), any(Authenticator.class)))
                    .thenReturn(session);
            sender.send(delivery("  "), channel("a@b.com"));
            mockedTransport.verify(() -> Transport.send(msgCap.capture()));
        }
        MimeMessage msg = (MimeMessage) msgCap.getValue();
        assertThat(msg.getSubject()).isEqualTo("notification4j 通知");
        assertThat(msg.getContent()).isEqualTo("notification4j 通知");
    }

    @Test
    void transport_exception_is_mapped_to_failed_send_result_with_simple_name() {
        conf.setMailHost("smtp.example.com");
        Session session = mockSession(); // 先建好（嵌套 stub 会触发 UnfinishedStubbing）
        try (var mockedSession = mockStatic(Session.class);
             var mockedTransport = mockStatic(Transport.class)) {
            mockedSession.when(() -> Session.getInstance(any(Properties.class), any(Authenticator.class)))
                    .thenReturn(session);
            mockedTransport.when(() -> Transport.send(any(Message.class)))
                    .thenThrow(new MessagingException("connect refused"));

            ChannelSender.SendResult r = sender.send(delivery("t"), channel("a@b.com"));

            assertThat(r.ok()).isFalse();
            assertThat(r.summary()).isEqualTo("SMTP 失败: MessagingException"); // 仅异常类名（防消息泄漏内部细节）
        }
    }

    @Test
    void session_build_failure_is_also_mapped_to_failed_send_result() {
        conf.setMailHost("smtp.example.com");
        try (var mockedSession = mockStatic(Session.class)) {
            mockedSession.when(() -> Session.getInstance(any(Properties.class), any(Authenticator.class)))
                    .thenThrow(new IllegalStateException("provider broken"));
            ChannelSender.SendResult r = sender.send(delivery("t"), channel("a@b.com"));
            assertThat(r.ok()).isFalse();
            assertThat(r.summary()).isEqualTo("SMTP 失败: IllegalStateException");
        }
    }

    @Test
    void null_target_title_and_channel_defaults_still_send_successfully() throws Exception {
        // 全缺省入参：title null、target 单地址、host 已配 —— 不抛即成功（鲁棒性口径）
        conf.setMailHost("smtp.example.com");
        NfyaDelivery d = new NfyaDelivery();
        Session session = mockSession();
        ArgumentCaptor<Message> msgCap = ArgumentCaptor.forClass(Message.class);
        try (var mockedSession = mockStatic(Session.class);
             var mockedTransport = mockStatic(Transport.class)) {
            mockedSession.when(() -> Session.getInstance(any(Properties.class), any(Authenticator.class)))
                    .thenReturn(session);
            ChannelSender.SendResult r = sender.send(d, channel("single@b.com"));
            assertThat(r.ok()).isTrue();
            mockedTransport.verify(() -> Transport.send(msgCap.capture()));
        }
        assertThat(msgCap.getValue().getAllRecipients()).hasSize(1);
    }
}
