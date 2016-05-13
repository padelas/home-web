package eu.daiad.web.model.message;

import org.joda.time.DateTime;

public class StaticRecommendation extends Message {

	private int id;

        private int index;
        
	private String title;

	private String description;

	private byte imageEncoded[];

	private String imageLink;

	private String prompt;

	private String externaLink;

	private String source;
        
        private DateTime createdOn;
        
        private DateTime modifiedOn;

        private boolean active;
        
	@Override
	public EnumMessageType getType() {
		return EnumMessageType.RECOMMENDATION_STATIC;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

        public int getIndex() {
		return index;
	}

	public void setIndex(int index) {
		this.index = index;
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

	public byte[] getImageEncoded() {
		return imageEncoded;
	}

	public void setImageEncoded(byte[] imageEncoded) {
		this.imageEncoded = imageEncoded;
	}

	public String getImageLink() {
		return imageLink;
	}

	public void setImageLink(String imageLink) {
		this.imageLink = imageLink;
	}

	public String getPrompt() {
		return prompt;
	}

	public void setPrompt(String prompt) {
		this.prompt = prompt;
	}

	public String getExternaLink() {
		return externaLink;
	}

	public void setExternaLink(String externaLink) {
		this.externaLink = externaLink;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

        public DateTime getCreatedOn() {
                return createdOn;
        }

        public void setCreatedOn(DateTime createdOn) {
                this.createdOn = createdOn;
        }

        public DateTime getModifiedOn() {
                return modifiedOn;
        }

        public void setModifiedOn(DateTime modifiedOn) {
                this.modifiedOn = modifiedOn;
        }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}