/**
 * Copyright (c) 2000-2010 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.mail.mailbox;

import com.liferay.mail.MailException;
import com.liferay.mail.NoSuchFolderException;
import com.liferay.mail.NoSuchMessageException;
import com.liferay.mail.imap.IMAPConnection;
import com.liferay.mail.imap.IMAPUtil;
import com.liferay.mail.model.Account;
import com.liferay.mail.model.Attachment;
import com.liferay.mail.model.Folder;
import com.liferay.mail.model.MailFile;
import com.liferay.mail.model.Message;
import com.liferay.mail.model.MessagesDisplay;
import com.liferay.mail.service.AccountLocalServiceUtil;
import com.liferay.mail.service.AttachmentLocalServiceUtil;
import com.liferay.mail.service.FolderLocalServiceUtil;
import com.liferay.mail.service.MessageLocalServiceUtil;
import com.liferay.mail.util.MailConstants;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.StringBundler;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.util.mail.InternetAddressUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import java.util.ArrayList;
import java.util.List;

import javax.mail.Address;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

/**
 * <a href="IMAPMailbox.java.html"><b><i>View Source</i></b></a>
 *
 * @author Scott Lee
 */
public class IMAPMailbox extends BaseMailbox {

	public Folder addFolder(String displayName)
		throws PortalException, SystemException {

		String[] names = _imapUtil.addFolder(displayName);

		return FolderLocalServiceUtil.addFolder(
			user.getUserId(), account.getAccountId(), names[0], names[1], 0);
	}

	public void afterPropertiesSet() {
		_imapUtil = new IMAPUtil(user, account);
	}

	public void deleteAttachment(long attachmentId)
		throws PortalException, SystemException {

		AttachmentLocalServiceUtil.deleteAttachment(attachmentId);
	}

	public void deleteFolder(long folderId)
		throws PortalException, SystemException {

		if ((account.getDraftFolderId() == folderId) ||
			(account.getInboxFolderId() == folderId) ||
			(account.getSentFolderId() == folderId) ||
			(account.getTrashFolderId() == folderId)) {

			throw new MailException(MailException.FOLDER_REQUIRED);
		}

		_imapUtil.deleteFolder(folderId);

		FolderLocalServiceUtil.deleteFolder(folderId);
	}

	public void deleteMessages(long folderId, long[] messageIds)
		throws PortalException, SystemException {

		if ((account.getDraftFolderId() != folderId) &&
			(account.getTrashFolderId() != folderId)) {

			Folder trashFolder = FolderLocalServiceUtil.getFolder(
				account.getTrashFolderId());

			_imapUtil.moveMessages(
				folderId, trashFolder.getFolderId(), messageIds, true);
		}
		else {
			_imapUtil.deleteMessages(folderId, messageIds);
		}
	}

	public InputStream getAttachment(long attachmentId)
		throws IOException, PortalException, SystemException {

		Attachment attachment = AttachmentLocalServiceUtil.getAttachment(
			attachmentId);

		Message message = MessageLocalServiceUtil.getMessage(
			attachment.getMessageId());

		if (account.getDraftFolderId() == attachment.getFolderId()) {
			return AttachmentLocalServiceUtil.getInputStream(
				attachmentId);
		}
		else {
			return _imapUtil.getAttachment(
				attachment.getFolderId(), message.getRemoteMessageId(),
				attachment.getContentPath());
		}
	}

	public Message getMessage(
			long folderId, String keywords, int messageNumber,
			String orderByField, String orderByType)
		throws PortalException, SystemException {

		MessagesDisplay messagesDisplay = getMessagesDisplay(
			folderId, keywords, messageNumber, 1, orderByField, orderByType);

		List<Message> messages = messagesDisplay.getMessages();

		return messages.get(0);
	}

	public MessagesDisplay getMessagesDisplay(
			long folderId, String keywords, int pageNumber, int messagesPerPage,
			String orderByField, String orderByType)
		throws PortalException, SystemException {

		if (orderByField.equals(MailConstants.ORDER_BY_ADDRESS)) {
			if (account.getSentFolderId() == folderId) {
				orderByField = "to";
			}
			else {
				orderByField = "sender";
			}
		}
		else if (!orderByField.equals(MailConstants.ORDER_BY_SENT_DATE) &&
				 !orderByField.equals(MailConstants.ORDER_BY_SIZE) &&
				 !orderByField.equals(MailConstants.ORDER_BY_SUBJECT)) {

			orderByField = MailConstants.ORDER_BY_SENT_DATE;
		}

		List<Message> messages = new ArrayList<Message>();

		int messageCount = MessageLocalServiceUtil.populateMessages(
			messages, folderId, keywords, pageNumber, messagesPerPage,
			orderByField, orderByType);

		if (Validator.isNotNull(keywords)) {
			return new MessagesDisplay(
				messages, pageNumber, messagesPerPage, messageCount);
		}
		else {
			Folder folder = FolderLocalServiceUtil.getFolder(folderId);

			return new MessagesDisplay(
				messages, pageNumber, messagesPerPage,
				folder.getRemoteMessageCount());
		}
	}

	public void moveMessages(long folderId, long[] messageIds)
		throws PortalException, SystemException {

		for (long messageId : messageIds) {
			Message message = MessageLocalServiceUtil.getMessage(messageId);

			Account account = AccountLocalServiceUtil.getAccount(
				message.getAccountId());

			long sourceFolderId = message.getFolderId();

			if ((account.getDraftFolderId() == sourceFolderId) ||
				(account.getSentFolderId() == sourceFolderId)) {

				throw new MailException(
					MailException.FOLDER_INVALID_DESTINATION);
			}

			_imapUtil.moveMessages(
				sourceFolderId, folderId, new long[] {messageId}, true);
		}
	}

	public InternetAddress[] parseAddresses(String addresses)
		throws PortalException {

		InternetAddress[] internetAddresses = new InternetAddress[0];

		try {
			internetAddresses = InternetAddress.parse(addresses, true);

			for (int i = 0; i < internetAddresses.length; i++) {
				InternetAddress internetAddress = internetAddresses[i];

				if (!Validator.isEmailAddress(internetAddress.getAddress())) {
					StringBundler sb = new StringBundler(4);

					sb.append(internetAddress.getPersonal());
					sb.append(StringPool.LESS_THAN);
					sb.append(internetAddress.getAddress());
					sb.append(StringPool.GREATER_THAN);

					throw new MailException(
						MailException.MESSAGE_INVALID_ADDRESS, sb.toString());
				}
			}
		}
		catch (AddressException ae) {
			throw new MailException(
				MailException.MESSAGE_INVALID_ADDRESS, ae, addresses);
		}

		return internetAddresses;
	}

	public void renameFolder(long folderId, String displayName)
		throws PortalException, SystemException {

		Folder folder = FolderLocalServiceUtil.getFolder(folderId);

		String[] names = _imapUtil.renameFolder(folderId, displayName);

		FolderLocalServiceUtil.updateFolder(
			folderId, names[0], names[1], folder.getRemoteMessageCount());
	}

	public Message saveDraft(
			long accountId, long messageId, String to, String cc, String bcc,
			String subject, String body, List<MailFile> mailFiles)
		throws PortalException, SystemException {

		Account account = AccountLocalServiceUtil.getAccount(accountId);

		StringBundler sb = new StringBundler();

		sb.append(user.getFullName());
		sb.append(" <");
		sb.append(account.getAddress());
		sb.append(StringPool.GREATER_THAN);

		String sender = sb.toString();

		Address[] toAddresses = parseAddresses(to);
		Address[] ccAddresses = parseAddresses(cc);
		Address[] bccAddresses = parseAddresses(bcc);

		if ((toAddresses.length == 0) && (ccAddresses.length == 0) &&
			(bccAddresses.length == 0)) {

			throw new MailException(MailException.MESSAGE_HAS_NO_RECIPIENTS);
		}

		if (messageId != 0) {
			return MessageLocalServiceUtil.updateMessage(
				messageId, account.getDraftFolderId(), sender,
				InternetAddressUtil.toString(toAddresses),
				InternetAddressUtil.toString(ccAddresses),
				InternetAddressUtil.toString(bccAddresses), null,
				subject, body, String.valueOf(MailConstants.FLAG_DRAFT), 0);
		}
		else {
			return MessageLocalServiceUtil.addMessage(
				user.getUserId(), account.getDraftFolderId(), sender, to, cc,
				bcc, null, subject, body,
				String.valueOf(MailConstants.FLAG_DRAFT), 0);
		}
	}

	public void sendMessage(long accountId, long messageId)
		throws PortalException, SystemException {

		Account account = AccountLocalServiceUtil.getAccount(accountId);

		Message message = MessageLocalServiceUtil.getMessage(messageId);

		Address[] toAddresses = parseAddresses(message.getTo());
		Address[] ccAddresses = parseAddresses(message.getCc());
		Address[] bccAddresses = parseAddresses(message.getBcc());

		if ((toAddresses.length == 0) && (ccAddresses.length == 0) &&
			(bccAddresses.length == 0)) {

			throw new MailException(MailException.MESSAGE_HAS_NO_RECIPIENTS);
		}

		List<Attachment> attachments =
			AttachmentLocalServiceUtil.getAttachments(messageId);

		List<MailFile> mailFiles = new ArrayList<MailFile>();

		for (Attachment attachment : attachments) {
			File file = AttachmentLocalServiceUtil.getFile(
				attachment.getAttachmentId());

			MailFile mailFile = new MailFile(
				file, attachment.getFileName(), attachment.getSize());

			mailFiles.add(mailFile);
		}

		_imapUtil.sendMessage(
			account.getPersonalName(), account.getAddress(), toAddresses,
			ccAddresses, bccAddresses, message.getSubject(), message.getBody(),
			mailFiles);
	}

	public void synchronize() throws PortalException, SystemException {
		if (_log.isDebugEnabled()) {
			_log.debug("Synchronizing account");
		}

		List<javax.mail.Folder> imapFolders = _imapUtil.getFolders();

		long draftFolderId = account.getDraftFolderId();
		long inboxFolderId = account.getInboxFolderId();
		long sentFolderId = account.getSentFolderId();
		long trashFolderId = account.getTrashFolderId();

		for (javax.mail.Folder imapFolder : imapFolders) {
			Folder folder = null;

			try {
				folder = FolderLocalServiceUtil.getFolder(
					account.getAccountId(), imapFolder.getFullName());
			}
			catch (NoSuchFolderException nsfe) {
				folder = FolderLocalServiceUtil.addFolder(
					user.getUserId(), account.getAccountId(),
					imapFolder.getFullName(), imapFolder.getName(), 0);
			}

			String folderName = imapFolder.getName().toLowerCase();

			if ((draftFolderId == 0) && folderName.contains("draft")) {
				draftFolderId = folder.getFolderId();
			}
			else if ((inboxFolderId == 0) && folderName.contains("inbox")) {
				inboxFolderId = folder.getFolderId();
			}
			else if ((sentFolderId == 0) && folderName.contains("sent")) {
				sentFolderId = folder.getFolderId();
			}
			else if ((trashFolderId == 0) && folderName.contains("trash")) {
				trashFolderId = folder.getFolderId();
			}
		}

		AccountLocalServiceUtil.updateFolders(
			account.getAccountId(), inboxFolderId, draftFolderId, sentFolderId,
			trashFolderId);

		if (_log.isDebugEnabled()) {
			_log.debug("Downloading new messages");
		}

		List<Folder> folders = FolderLocalServiceUtil.getFolders(
			account.getAccountId());

		for (Folder folder : folders) {
			synchronizeFolder(folder.getFolderId());
		}
	}

	public void synchronizeFolder(long folderId)
		throws PortalException, SystemException {

		if (_log.isDebugEnabled()) {
			_log.debug("Synchronizing folder " + folderId);
		}

		_imapUtil.storeEnvelopes(folderId);
	}

	public void synchronizeMessage(long messageId)
		throws PortalException, SystemException {

		Message message = MessageLocalServiceUtil.getMessage(messageId);

		long remoteMessageId = message.getRemoteMessageId();

		if (remoteMessageId == 0) {
			return;
		}

		try {
			_imapUtil.storeMessages(
				message.getFolderId(),
				new long[] {message.getRemoteMessageId()});
		}
		catch (IOException ioe) {
			throw new MailException(ioe);
		}
	}

	public void synchronizePage(
			long folderId, int pageNumber, int messagesPerPage)
		throws PortalException, SystemException {

		long[] remoteMessageIds = _imapUtil.getMessageUIDs(
			folderId, pageNumber, messagesPerPage);

		List<Long> missingRemoteMessageIds = new ArrayList<Long>();

		for (long remoteMessageId : remoteMessageIds) {
			try {
				MessageLocalServiceUtil.getMessage(folderId, remoteMessageId);
			}
			catch (NoSuchMessageException nsme) {
				missingRemoteMessageIds.add(remoteMessageId);
			}
		}

		if (!missingRemoteMessageIds.isEmpty()) {
			//_imapUtil.storeEnvelopes(folderId, remoteMessageIds);
		}
	}

	public void updateFlags(
			long folderId, long[] messageIds, int flag, boolean value)
		throws PortalException, SystemException {

		Folder folder = FolderLocalServiceUtil.getFolder(folderId);

		Account account = AccountLocalServiceUtil.getAccount(
			folder.getAccountId());

		if (account.getDraftFolderId() == folder.getFolderId()) {
			_imapUtil.updateFlags(
				folder.getFolderId(), messageIds, flag, value, false);
		}
		else {
			_imapUtil.updateFlags(
				folder.getFolderId(), messageIds, flag, value, true);
		}
	}

	public void validateAccount(
			String incomingHostName, int incomingPort, boolean incomingSecure,
			String outgoingHostName, int outgoingPort, boolean outgoingSecure,
			String login, String password)
		throws PortalException {

		IMAPConnection imapConnection = new IMAPConnection(
			incomingHostName, incomingPort, incomingSecure, outgoingHostName,
			outgoingPort, outgoingSecure, login, password);

		imapConnection.testConnection();
	}

	private static Log _log = LogFactoryUtil.getLog(IMAPMailbox.class);

	private IMAPUtil _imapUtil;

}