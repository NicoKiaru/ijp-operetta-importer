package ch.epfl.biop.operetta.commands;

import ch.epfl.biop.operetta.OperettaManager;
import ch.epfl.biop.operetta.commands.utils.ListChooser;
import ch.epfl.biop.operetta.commands.utils.TiledCellReader;
import ch.epfl.biop.operetta.utils.HyperRange;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import net.imagej.ImageJ;
import net.imagej.ImgPlus;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import ome.xml.model.Well;
import ome.xml.model.WellSample;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.InteractiveCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;
import org.scijava.widget.FileWidget;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Plugin( type = Command.class )
public class OperettaImporterInteractive extends InteractiveCommand {

    @Parameter( label = "Downsample Factor" )
    int downsample = 4;

    @Parameter( label = "Selected Wells. Leave blank for all", required = false )
    private String selected_wells_str = "";

    @Parameter( label = "Choose Wells", callback = "wellChooser", required = false, persist = false)
    private Button chooseWells;

    @Parameter( label = "Selected Fields. Leave blank for all", required = false )
    private String selected_fields_str = "";

    @Parameter( label = "Fuse Fields", required = false )
    private boolean is_fuse_fields = true;

    @Parameter( label = "Choose Fields", callback = "fieldChooser", required = false, persist = false )
    private Button chooseFields;

    @Parameter( label = "Roi Coordinates [x,y,w,h]. . Leave blank for full image", required = false )
    private String roi_bounds = "";

    @Parameter( label = "Open Well Slice", callback = "roiChooser", required = false, persist = false )
    private Button openSlice;

    @Parameter( label = "Get Roi From Open Well", callback = "roiSelector", required = false, persist = false )
    private Button selectRoi;

    @Parameter( label = "Select Range", visibility = ItemVisibility.MESSAGE, persist = false, required = false)
    String range = "You can use commas or colons to separate ranges. eg. '1:10' or '1,3,5,8' ";

    @Parameter( label = "Selected Channels. Leave blank for all", required = false )
    private String selected_channels_str = "";

    @Parameter( label = "Selected Slices. Leave blank for all", required = false )
    private String selected_slices_str = "";

    @Parameter( label = "Selected Timepoints. Leave blank for all", required = false )
    private String selected_timepoints_str = "";

    @Parameter( label = "Perform Projection of Data" )
    boolean is_projection = false;

    @Parameter( label = "Projection Type", choices = {"Average Intensity", "Max Intensity", "Min Intensity", "Sum Slices", "Standard Deviation", "Median"} )
    String z_projection_method = "Max Intensity";

    @Parameter( label = "Save Directory", style = FileWidget.DIRECTORY_STYLE )
    File save_directory = new File( System.getProperty( "user.home" ) + File.separator );

    @Parameter( label = "Choose Data Range", visibility = ItemVisibility.MESSAGE, persist = false, required = false)
    String norm = "Important if you have digital phase images";

    @Parameter( label = "Min Value" )
    Integer norm_min = 0;

    @Parameter( label = "Max Value" )
    Integer norm_max = (int) Math.pow( 2, 16 ) - 1;

    @Parameter( label = "Process", callback = "doProcess", persist = false )
    Button process;

    @Parameter( required = false )
    OperettaManager.Builder opmBuilder;

    OperettaManager opm;

    List<String> selected_wells_string = new ArrayList<>( );
    List<String> selected_fields_string = new ArrayList<>( );

    private ImagePlus roiImage;

    private void roiSelector( ) {
        if ( this.roiImage != null ) {
            Roi roi = this.roiImage.getRoi( );

            if ( roi != null ) {
                this.roi_bounds = String.format( "%d, %d, %d, %d",
                        roi.getBounds( ).x * 8,
                        roi.getBounds( ).y * 8,
                        roi.getBounds( ).width * 8,
                        roi.getBounds( ).height * 8 );
            }
        }
    }

    private void wellChooser( ) {
        opm = opmBuilder.build( );
        ListChooser.create( "Wells", opm.getAvailableWellsString( ), selected_wells_string );
        selected_wells_str = selected_wells_string.toString( );
    }

    private void fieldChooser( ) {
        opm = opmBuilder.build( );
        ListChooser.create( "Fields", opm.getAvailableFieldsString( ), selected_fields_string );
        selected_fields_str = selected_fields_string.toString( );
    }

    private List<String> stringToList( String str ) {
        String[] split = str.replaceAll( "\\[|\\]", "" ).split( "," );

        List<String> result = Arrays.asList( split ).stream( ).collect( Collectors.toList( ) );

        return result;
    }

    private void roiChooser( ) {
        opm = opmBuilder
                .doProjection( is_projection )
                .setProjectionMethod( z_projection_method )
                .build( );

        // If there is a range, update it, otherwise choose the first timepoint and the first z
        if( !this.selected_slices_str.equals( "" ) ) {
            opm.getRange().updateZRange( selected_slices_str );
        } else if( this.selected_slices_str.equals( "" ) && !this.is_projection ) {
            opm.getRange().updateZRange( "1:1" );
        }

        if( !this.selected_timepoints_str.equals( "" ) ) {
            opm.getRange().updateTRange( selected_timepoints_str );
        } else if( this.selected_timepoints_str.equals( "" ) && !this.is_projection ) {
            opm.getRange().updateTRange( "1:1" );
        }

        // Choose well to display
        String selected_well;


        // Get the first well that is selected
        if ( selected_wells_str.length( ) != 0 )
            selected_well = stringToList( selected_wells_str ).get( 0 );
        else {
            selected_well = opm.getAvailableWellsString( ).get( 0 );
        }

        int row = getRow( selected_well );
        int col = getColumn( selected_well );
        Well well = opm.getWell( row, col );

        ImagePlus sample;
        if (!is_fuse_fields && !selected_fields_string.equals( "" ) ) {
            WellSample field = opm.getField( well, getFields().get( 0 ));

            sample =  opm.getFieldImage( field, 8  );

        } else {
            sample = opm.getWellImage( well, 8 );

        }
        sample.show( );
        this.roiImage = sample;

    }

    private void roiChooserLazy( ) {
        opm = opmBuilder
                .doProjection( is_projection )
                .setProjectionMethod( z_projection_method )
                .build( );

        IJ.log("Current Range: "+opm.getRange().toString());
        // If there is a range, update it, otherwise choose the first timepoint and the first z
        if( !this.selected_slices_str.equals( "" ) ) {
            opm.getRange().updateZRange( selected_slices_str );
        } else if( this.selected_slices_str.equals( "" ) && this.is_projection ) {
            opm.getRange().updateZRange( "1:1" );
        }

        if( !this.selected_timepoints_str.equals( "" ) ) {
            opm.getRange().updateTRange( selected_timepoints_str );
        } else if( this.selected_timepoints_str.equals( "" ) && this.is_projection ) {
            opm.getRange().updateTRange( "1:1" );
        }

        // Choose well to display
        String selected_well;


        // Get the first well that is selected
        if ( selected_wells_str.length( ) != 0 )
            selected_well = stringToList( selected_wells_str ).get( 0 );
        else {
            selected_well = opm.getAvailableWellsString( ).get( 0 );
        }

        int row = getRow( selected_well );
        int col = getColumn( selected_well );
        Well well = opm.getWell( row, col );

        ImagePlus sample;

        ImgPlus<UnsignedShortType> image = TiledCellReader.createLazyImage( opm, well, 16 );


        //ij.ui( ).show( image );

        sample = ImageJFunctions.wrap(image, "test" );
        sample.setTitle( "Well: "+selected_well );
        sample.show();
        this.roiImage = sample;

    }
    int getRow( String well_str ) {
        Pattern p = Pattern.compile( "R(\\d)-C(\\d)" );
        Matcher m = p.matcher( well_str );
        if ( m.find( ) ) {
            return Integer.parseInt( m.group( 1 ) );
        }
        return -1;
    }

    int getColumn( String well_str ) {
        Pattern p = Pattern.compile( "R(\\d)-C(\\d)" );
        Matcher m = p.matcher( well_str );
        if ( m.find( ) ) {
            return Integer.parseInt( m.group( 2 ) );
        }
        return -1;
    }

    private List<Integer> getFields( ) {
        if ( selected_fields_string.size() != 0 ) {
            List<Integer> field_ids = selected_fields_string.stream( ).map( w -> Integer.parseInt( w.trim( ).split( " " )[ 1 ] ) - 1 ).collect( Collectors.toList( ) );
            return field_ids;
        } else {
            return opm.getAvailableFieldIds();
        }
    }

    public void doProcess( ) {
        HyperRange range = new HyperRange.Builder( )
                .setRangeC( this.selected_channels_str )
                .setRangeZ( this.selected_slices_str )
                .setRangeT( this.selected_timepoints_str )
                .build( );

        opm = opmBuilder
                .setRange( range )
                .setProjectionMethod( this.z_projection_method )
                .doProjection( this.is_projection )
                .setSaveFolder( this.save_directory )
                .setNormalization( norm_min, norm_max )

                .build( );

        // Get Wells and Fields

        List<String> selected_wells = opm.getAvailableWellsString( );
        List<String> selected_fields = opm.getAvailableFieldsString( );


        if (!selected_wells_str.equals( "" )) {
            selected_wells = stringToList( selected_wells_str );
        }


        if (!selected_fields_str.equals( "" )) {
            selected_fields = stringToList( selected_fields_str );
        }

        // Get the actual field and well ids
        List<Well> wells = selected_wells.stream().map( w -> {
            int row = getRow( w );
            int col = getColumn( w );
            return opm.getWell( row, col);
        } ).collect( Collectors.toList());

        List<Integer> field_ids = selected_fields.stream().map( w -> Integer.parseInt( w.trim( ).split( " " )[ 1 ]) - 1 ).collect( Collectors.toList());


        Roi roi = parseRoi( roi_bounds );

        // Write the associated macro command in new thread to allow for proper logging
        new Thread(()->opm.process( wells, field_ids, this.downsample, roi, !is_fuse_fields)).start();

    }

    private Roi parseRoi( String roi_string ) {

        Roi bounds = null;
        if (roi_string.length() != 0 ) {
            String[] s = roi_string.split(",");
            if (s.length == 4)
                bounds = new Roi( Integer.parseInt( s[0].trim() ),  Integer.parseInt( s[1].trim() ),  Integer.parseInt( s[2].trim() ),  Integer.parseInt( s[3].trim() ) );
        }
        return bounds;
    }

    public static void main( final String... args ) throws Exception {
        // create the ImageJ application context with all available services
        final ImageJ ij = new ImageJ( );
        ij.ui( ).showUI( );

        // invoke the plugin
        ij.command( ).run( OperettaImporterInteractive.class, true );
    }
}
