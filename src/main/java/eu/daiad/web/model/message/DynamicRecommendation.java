package eu.daiad.web.model.message;

import org.joda.time.DateTime;

public class DynamicRecommendation extends Message {

	private int id;

	private EnumDynamicRecommendationType recommendation;

	private int priority;

	private String title;

	private String description;

	private String imageLink;

	private DateTime createdOn;

	public DynamicRecommendation(EnumDynamicRecommendationType recommendation) {
		this.recommendation = recommendation;
	}

	@Override
	public EnumMessageType getType() {
		return EnumMessageType.RECOMMENDATION_DYNAMIC;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public int getPriority() {
		return priority;
	}

	public void setPriority(int priority) {
		this.priority = priority;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public DateTime getCreatedOn() {
		return createdOn;
	}

	public void setCreatedOn(DateTime createdOn) {
		this.createdOn = createdOn;
	}

	public String getImageLink() {
		return imageLink;
	}

	public void setImageLink(String imageLink) {
		this.imageLink = imageLink;
	}

	public EnumDynamicRecommendationType getRecommendation() {
		return recommendation;
	}

}
