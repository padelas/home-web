
var _ = require('lodash');
var moment = require('moment');

var React = require('react');
var ReactDOM = require('react-dom');
var Redux = require('react-redux');
var Bootstrap = require('react-bootstrap');
var Breadcrumb = require('../../Breadcrumb');

var {MeasurementsReportPanel} = require('../../reports');

var PropTypes = React.PropTypes;
var {configPropType} = require('../../../prop-types'); 

var Page = React.createClass({
  displayName: 'Analytics.ReportPanel',

  propTypes: {
    routes: PropTypes.array, // supplied by react-router
    config: configPropType,
  },

  contextTypes: {
    intl: React.PropTypes.object
  },

  render: function() {  
    var {routes, config} = this.props;
    return (
      <div className="container-fluid">
        <div className="row">
          <div className="col-md-12">
            <Breadcrumb routes={routes}/>
          </div>
        </div>
        <div className="row">
          <div className="col-md-12">
            <MeasurementsReportPanel config={config} />
          </div>
        </div>
      </div>
    );
  },

});

Page.icon = 'pie-chart';
Page.title = 'Section.Analytics.ReportPanel';

function mapStateToProps(state, ownProps) {
  return {
    config: state.config,
  };
}

function mapDispatchToProps(dispatch, ownProps) {
  return {};
}

module.exports = Redux.connect(mapStateToProps, mapDispatchToProps)(Page);
