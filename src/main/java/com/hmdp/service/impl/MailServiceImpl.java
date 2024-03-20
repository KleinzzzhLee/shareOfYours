package com.hmdp.service.impl;

import cn.hutool.core.date.DateTime;
import com.hmdp.entity.vo.MailVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import javax.mail.MessagingException;

@Slf4j
@Service
public class MailServiceImpl {

    @Value("${spring.mail.username}")
    private String USER;

    @Value("${spring.mail.password}")
    private String PASSWORD;

    @Resource
    private JavaMailSenderImpl mailSender;


    private Boolean checkMail(MailVO mail) {
        if (StringUtils.isEmpty(mail.getTo())) {
            return false;
        } else if(StringUtils.isEmpty(mail.getCode())) {
            return false;
        }
        return true;
    }


    public Boolean senMail(MailVO mail) {
        if(!checkMail(mail)) {
            return false;
        }

        try {
            // kp 可以帮助你创建复杂的邮件消息，比如包含附件或是富文本的邮件。
            MimeMessageHelper messageHelper = new MimeMessageHelper(mailSender.createMimeMessage(), true);
            messageHelper.setFrom(USER);
            messageHelper.setTo(mail.getTo());
            messageHelper.setSubject("甄选点评：：验证码信息");
            messageHelper.setText("您的验证码为" + mail.getCode() +",在五分钟内有效，请勿外传。");
            messageHelper.setSentDate(DateTime.now());
            mailSender.send(messageHelper.getMimeMessage());
        } catch (Exception e) {
            return false;
        }
        return true;
    }

}
