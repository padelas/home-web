var React = require('react');

var CellCheckbox = React.createClass({
	getDefaultProps: function() {
		return {
			disabled: false,
			text: '',
			checked: false,
			onChange: null
	    };
	},
	
	handleChange: function(){
		this.props.onUserClick(
			this.props.rowId,
			this.props.propertyName,
			this.props.checked
		);
	},
	
  	render: function() {
  		var classNames = 'checkbox c-checkbox';
  		if (this.props.draftFlag){
  			classNames = classNames + ' c-checkbox-warning';
  		} else if (this.props.disabled){
  			classNames = classNames + ' c-checkbox-disabled';
  		}
  		
  		if(this.props.text) {
	  		return (
				<div className={classNames}>
					<label>
						<input type='checkbox' 
							disabled={this.props.disabled ? "disabled" : false} checked={this.props.checked}
						    ref="checkbox"
							onChange={this.handleChange}
							/>
							<span className='fa fa-check' style={{ marginRight: 30 }}></span>
							{' ' + this.props.text}
					</label>
				</div>
	 		);
  		}
  		return (
			<div className={classNames}>
				<label>
					<input type='checkbox' 
						disabled={this.props.disabled ? "disabled" : false} checked={this.props.checked}
						ref="checkbox"
						onChange={this.handleChange}
						/>
						<span className='fa fa-check' style={{ marginRight: 0 }}></span>
				</label>
			</div>
 		);
  	}
});

module.exports = CellCheckbox;
