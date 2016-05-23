package eu.daiad.web.controller.action;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import eu.daiad.web.controller.BaseController;
import eu.daiad.web.model.RestResponse;
import eu.daiad.web.model.error.ApplicationException;
import eu.daiad.web.model.message.EnumMessageType;
import eu.daiad.web.model.message.Message;
import eu.daiad.web.model.message.MessageAcknowledgementRequest;
import eu.daiad.web.model.message.MessageRequest;
import eu.daiad.web.model.message.MultiTypeMessageResponse;
import eu.daiad.web.model.message.SingleTypeMessageResponse;
import eu.daiad.web.model.security.AuthenticatedUser;
import eu.daiad.web.repository.application.IMessageRepository;

@RestController
public class MessageController extends BaseController {

	private static final Log logger = LogFactory.getLog(MessageController.class);

	@Autowired
	private IMessageRepository messageRepository;

	@RequestMapping(value = "/action/message", method = RequestMethod.POST, produces = "application/json")
	@Secured("ROLE_USER")
	public RestResponse getMessages(@RequestBody MessageRequest request) {
		try {
			MultiTypeMessageResponse messageResponse = new MultiTypeMessageResponse();

			for (Message message : this.messageRepository.getMessages(request)) {
				switch (message.getType()) {
					case ALERT:
						messageResponse.getAlerts().add(message);
						break;
					case RECOMMENDATION_STATIC:
						messageResponse.getTips().add(message);
						break;
					case RECOMMENDATION_DYNAMIC:
						messageResponse.getRecommendations().add(message);
						break;
					case ANNOUNCEMENT:
						messageResponse.getAnnouncements().add(message);
						break;
					default:
						// Ignore
				}
			}

			return messageResponse;
		} catch (ApplicationException ex) {
			if (!ex.isLogged()) {
				logger.error(ex.getMessage(), ex);
			}

			RestResponse response = new RestResponse();
			response.add(this.getError(ex));
			return response;
		}
	}

	@RequestMapping(value = "/action/message/acknowledge", method = RequestMethod.POST, produces = "application/json")
	@Secured("ROLE_USER")
	public RestResponse recieveMessageAcknowledge(@RequestBody MessageAcknowledgementRequest request) {
		RestResponse response = new RestResponse();

		try {
			this.messageRepository.setMessageAcknowledgement(request.getMessages());
		} catch (ApplicationException ex) {
			if (!ex.isLogged()) {
				logger.error(ex.getMessage(), ex);
			}

			response.add(this.getError(ex));
		}
		return response;
	}

	@RequestMapping(value = "/action/recommendation/static/{locale}", method = RequestMethod.GET, produces = "application/json")
	@Secured("ROLE_ADMIN")
	public RestResponse getRecommendations(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String locale) {
		try {
			SingleTypeMessageResponse messages = new SingleTypeMessageResponse();

			messages.setType(EnumMessageType.RECOMMENDATION_STATIC);
			messages.setMessages(this.messageRepository.getAdvisoryMessages(locale));

			return messages;
		} catch (ApplicationException ex) {
			if (!ex.isLogged()) {
				logger.error(ex.getMessage(), ex);
			}

			RestResponse response = new RestResponse();
			response.add(this.getError(ex));
			return response;
		}
	}

}
