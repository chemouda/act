##########################################################################
#                                                                        #
#  This file is part of the 20n/act project.                             #
#  20n/act enables DNA prediction for synthetic biology/bioengineering.  #
#  Copyright (C) 2017 20n Labs, Inc.                                     #
#                                                                        #
#  Please direct all queries to act@20n.com.                             #
#                                                                        #
#  This program is free software: you can redistribute it and/or modify  #
#  it under the terms of the GNU General Public License as published by  #
#  the Free Software Foundation, either version 3 of the License, or     #
#  (at your option) any later version.                                   #
#                                                                        #
#  This program is distributed in the hope that it will be useful,       #
#  but WITHOUT ANY WARRANTY; without even the implied warranty of        #
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         #
#  GNU General Public License for more details.                          #
#                                                                        #
#  You should have received a copy of the GNU General Public License     #
#  along with this program.  If not, see <http://www.gnu.org/licenses/>. #
#                                                                        #
##########################################################################

# server.R performs the computations behind the scenes
# It collects a list of inputs from ui.R and produces a list of output

# Note that the R libraries "shiny" and "rscala" well as Scala should be installed on the machine.
# To perform these tasks, please run in R:
# install.packages(c("shiny", "rscala"))
# scalaInstall()

# Finally, this assumes that two symlinks have been created and are located in the app directory:
# reachables-assembly-0.1.jar -> symlink to a "fat jar" created through sbt assembly
# 20nlogo -> symlink to the 20n logo in the resources directory
kFatJarLocation <- "reachables-assembly-0.1.jar"

# Constants
kImportBingPackageCommand <- 'import act.installer.bing'
kScalaCommand <- 'bing.ExploreRange.getOutcomeVsYieldTable(%s, %s, "CMOS", "%s")'

k20nLogoLocation <- "20nlogo"
kChartLabelSizeFactor <- 1.3

library(shiny)
library(rscala)

getData <- function(input, sc) {
  titer = input$titer
  price = input$market.price
  location <- input$location
  command <- sprintf(kScalaCommand, titer, price, location)
  out <- sc%~%command
  con <- textConnection(out)
  on.exit(close(con))
  table <- read.table(con, header = TRUE)
  table
}

getBreakEvenPoint <- function(data) {
  which.min(abs(data$ROIPercent))
}

plotGraph <- function(input, data, outcome) {
  d <- data
  i <- getBreakEvenPoint(d)

  switch(outcome,
         ROI = {
           yValues <- d$ROIPercent
           yLabel <- "ROI (%)"
           rect.y.top <- 0
           rect.y.bottom <- -100000
         },
         NPV = {
           yValues <- d$NPV
           yLabel <- "NPV ($$M)"
           rect.y.top <- 0
           rect.y.bottom <- -10000
         },
         Yield = {
           yValues <- d$Yield
           yLabel <- "Yield (g/L)"
           rect.y.top <- d$Yield[i]
           rect.y.bottom <- -100
         },
         COGS = {
           yValues <- d$COGS
           yLabel <- "COGS ($$/T)"
           rect.y.bottom <- input$market.price
           rect.y.top <- 1000000
         })
  switch(input$x.axis,
         InvestmentUSD = {
           xValues <- d$InvestM
           xLabel <- "Investment ($$M)"
           xLim <- input$investment.usd.max.min
         },
         InvestmentYears = {
           xValues <- d$InvestY
           xLabel <- "Investment (Years)"
           xLim <- input$investment.years.max.min
        })
  # Line plot for the chart
  plot(xValues, yValues, type="l", col="blue",lwd=3, ylab=yLabel, xlab=xLabel, xlim=xLim, main=yLabel,
       cex.lab=kChartLabelSizeFactor, cex.axis=kChartLabelSizeFactor,
       cex.main=kChartLabelSizeFactor, cex.sub=kChartLabelSizeFactor)
  # Breakeven boundaries
  rect(0, rect.y.bottom, 30, rect.y.top, density = 3, col = "red")
}

shinyServer(function(input, output, session) {
  
  sc=scalaInterpreter(kFatJarLocation)
  sc%~%kImportBingPackageCommand
  
  output$logo <- renderImage({
    list(src = k20nLogoLocation,
         contentType = 'image/png',
         width = "200",
         height = "120",
         alt = "20n Logo")
  }, deleteFile = FALSE)
  
  data <- reactive({
    getData(input, sc)
  })
  
  output$plot1 <- renderPlot({
    plotGraph(input, data(), "ROI")
  })
  
  output$plot2 <- renderPlot({
    plotGraph(input, data(), "NPV")
  })
  
  output$plot3 <- renderPlot({
    plotGraph(input, data(), "Yield")
  })
  
  output$plot4 <- renderPlot({
    plotGraph(input, data(), "COGS")
  })
})
