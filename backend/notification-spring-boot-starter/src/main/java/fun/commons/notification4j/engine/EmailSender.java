package fun.commons.notification4j.engine;

import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaDelivery;
import fun.commons.notification4j.properties.NfyProperties;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;

import java.util.Properties;

/**
 * EMAIL 通道投递（Jakarta Mail SMTP；2xx/无异常=成功）。
 * mailHost 未配置时直接投递失败（走引擎重试→DEAD），配置见 nfy.engine.mail-*。
 */
@RequiredArgsConstructor
public class EmailSender implements ChannelSender {

    private final NfyProperties.Engine engine;

    @Override
    public String channelType() {
        return "EMAIL";
    }

    @Override
    public SendResult send(NfyaDelivery d, NfyaChannel ch) {
        if (engine.getMailHost() == null || engine.getMailHost().isBlank()) {
            return SendResult.fail("SMTP 未配置(nfy.engine.mail-host)");
        }
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", engine.getMailHost());
            props.put("mail.smtp.port", String.valueOf(engine.getMailPort()));
            props.put("mail.smtp.auth", String.valueOf(engine.getMailUsername() != null && !engine.getMailUsername().isBlank()));
            // 评审第 6b 步 P1：STARTTLS（防凭据明文过网）+ 显式超时（防半开连接挂死 worker）
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "30000");
            props.put("mail.smtp.writetimeout", "30000");
            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(engine.getMailUsername(), engine.getMailPassword());
                }
            });
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(engine.getMailFrom() == null || engine.getMailFrom().isBlank()
                    ? "notification4j@localhost" : engine.getMailFrom()));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(ch.getTarget()));
            String title = d.getTitle() == null || d.getTitle().isBlank() ? "notification4j 通知" : d.getTitle();
            msg.setSubject(title, "UTF-8");
            msg.setText(title, "UTF-8");
            Transport.send(msg);
            return SendResult.success("SMTP 2xx");
        } catch (Exception e) {
            return SendResult.fail("SMTP 失败: " + e.getClass().getSimpleName());
        }
    }
}
