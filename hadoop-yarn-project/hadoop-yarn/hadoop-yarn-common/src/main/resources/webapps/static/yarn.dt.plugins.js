if (!jQuery.fn.dataTableExt.fnVersionCheck("1.7.5")) {
  alert("These plugins requires dataTables 1.7.5+");
}

// don't filter on hidden html elements for an sType of title-numeric
$.fn.dataTableExt.ofnSearch['title-numeric'] = function ( sData ) {
   return sData.replace(/\n/g," ").replace( /<.*?>/g, "" );
}

// 'title-numeric' sort type
jQuery.fn.dataTableExt.oSort['title-numeric-asc']  = function(a,b) {
  var x = a.match(/title=["']?(-?\d+\.?\d*)/)[1];
  var y = b.match(/title=["']?(-?\d+\.?\d*)/)[1];
  x = parseFloat( x );
  y = parseFloat( y );
  return ((x < y) ? -1 : ((x > y) ?  1 : 0));
};

jQuery.fn.dataTableExt.oSort['title-numeric-desc'] = function(a,b) {
  var x = a.match(/title=["']?(-?\d+\.?\d*)/)[1];
  var y = b.match(/title=["']?(-?\d+\.?\d*)/)[1];
  x = parseFloat( x );
  y = parseFloat( y );
  return ((x < y) ?  1 : ((x > y) ? -1 : 0));
};

jQuery.fn.dataTableExt.oApi.fnSetFilteringDelay = function ( oSettings, iDelay ) {
  var
  _that = this,
  iDelay = (typeof iDelay == 'undefined') ? 250 : iDelay;

  this.each( function ( i ) {
    $.fn.dataTableExt.iApiIndex = i;
    var
    $this = this,
    oTimerId = null,
    sPreviousSearch = null,
    anControl = $( 'input', _that.fnSettings().aanFeatures.f );

    anControl.unbind( 'keyup' ).bind( 'keyup', function() {
      var $$this = $this;

      if (sPreviousSearch === null || sPreviousSearch != anControl.val()) {
        window.clearTimeout(oTimerId);
        sPreviousSearch = anControl.val();
        oSettings.oApi._fnProcessingDisplay(oSettings, true);
        oTimerId = window.setTimeout(function() {
          $.fn.dataTableExt.iApiIndex = i;
          _that.fnFilter( anControl.val() );
          oSettings.oApi._fnProcessingDisplay(oSettings, false);
        }, iDelay);
      }
    });
    return this;
  } );
  return this;
}

function renderHadoopDate(data, type, full) {
  if (type === 'display') {
    if(data === '0') {
      return "N/A";
    }
    return new Date(parseInt(data)).toUTCString();
  }
  // 'filter', 'sort', 'type' and undefined all just use the number
  // If date is 0, then for purposes of sorting it should be consider max_int
  return data === '0' ? '9007199254740992' : data;  
}

function renderHadoopElapsedTime(data, type, full) {
  if (type === 'display') {
    var timeDiff = parseInt(data);
    if(timeDiff < 0)
      return "N/A";
    
    var hours = Math.floor(timeDiff / (60*60*1000));
    var rem = (timeDiff % (60*60*1000));
    var minutes =  Math.floor(rem / (60*1000));
    rem = rem % (60*1000);
    var seconds = Math.floor(rem / 1000);
    
    var toReturn = "";
    if (hours != 0){
      toReturn += hours;
      toReturn += "hrs, ";
    }
    if (minutes != 0){
      toReturn += minutes;
      toReturn += "mins, ";
    }
    toReturn += seconds;
    toReturn += "sec";
    return toReturn;
  }
  // 'filter', 'sort', 'type' and undefined all just use the number
  return data;  
}

function parseHadoopID(data, type, full) {
  if (type === 'display' || type === 'filter') {
    return data;
  }
  //Parse the ID for 'sort', 'type' and undefined
  //The number after the last '_' and before the end tag '<'
  var splits = data.split('_');
  return splits[parseInt(splits.length-1)].split('<')[0];
}

function parseHadoopProgress(data, type, full) {
  if (type === 'display') {
    return data;
  }
  //Return the title attribute for 'sort', 'filter', 'type' and undefined
  return data.split("'")[1];
}
